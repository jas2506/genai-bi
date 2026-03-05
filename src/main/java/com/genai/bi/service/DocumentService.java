package com.genai.bi.service;


import com.genai.bi.model.DocumentChunk;
import com.genai.bi.model.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the full document upload pipeline.
 *
 * Upload Pipeline (all 5 stages in sequence):
 *
 *   MultipartFile (PDF)
 *       │
 *       ▼ TikaExtractorService
 *   Raw Text String
 *       │
 *       ▼ ContentClassifierService
 *   List<TextBlock>  (NARRATIVE | TABLE | HEADER)
 *       │
 *       ▼ TextChunkerService
 *   List<DocumentChunk>  (content + rawContent + sectionTitle)
 *       │
 *       ▼ EmbeddingService (OpenRouter batch)
 *   List<DocumentChunk>  (now with float[] embedding)
 *       │
 *       ▼ VectorStoreService (pgvector JDBC batch insert)
 *   Stored in PostgreSQL ✓
 *
 * Estimated timing for a 100-page 10-K:
 *   - Tika extraction:   ~2s
 *   - Classification:    ~0.1s
 *   - Chunking:          ~0.1s
 *   - Embedding (80 chunks, 4 batch calls): ~4s
 *   - DB insert:         ~0.5s
 *   Total: ~7s
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final TikaExtractorService tikaExtractorService;
    private final ContentClassifierService contentClassifierService;
    private final TextChunkerService textChunkerService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    public DocumentService(
            TikaExtractorService tikaExtractorService,
            ContentClassifierService contentClassifierService,
            TextChunkerService textChunkerService,
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService
    ) {
        this.tikaExtractorService = tikaExtractorService;
        this.contentClassifierService = contentClassifierService;
        this.textChunkerService = textChunkerService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
    }

    /**
     * Full upload pipeline for a PDF or DOCX file.
     *
     * @param file  The uploaded document
     * @return      Summary map: {sourceId, filename, totalChunks, tablesDetected, narrativeChunks}
     */
    public Map<String, Object> processDocument(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        log.info("Processing document: {}", filename);

        // ── Stage 1: Extract text ──────────────────────────────────────────
        log.info("Stage 1: Extracting text with Tika...");
        String rawText = tikaExtractorService.extractText(file);
        log.info("Extracted {} characters", rawText.length());

        // ── Stage 2: Classify blocks ───────────────────────────────────────
        log.info("Stage 2: Classifying content blocks...");
        List<TextBlock> blocks = contentClassifierService.classify(rawText);
        long tableBlocks = blocks.stream().filter(b -> b.getType().name().equals("TABLE")).count();
        long narrativeBlocks = blocks.stream().filter(b -> b.getType().name().equals("NARRATIVE")).count();
        log.info("Found {} table blocks, {} narrative blocks", tableBlocks, narrativeBlocks);

        // ── Stage 3: Chunk ─────────────────────────────────────────────────
        log.info("Stage 3: Chunking blocks...");
        List<DocumentChunk> chunks = textChunkerService.chunkBlocks(blocks);
        log.info("Created {} chunks total", chunks.size());

        if (chunks.isEmpty()) {
            throw new RuntimeException("No content chunks could be created from the document. " +
                    "Check that the PDF has selectable text.");
        }

        // ── Stage 4: Embed ─────────────────────────────────────────────────
        log.info("Stage 4: Generating embeddings for {} chunks...", chunks.size());
        List<String> texts = chunks.stream()
                .map(DocumentChunk::getContent)
                .collect(Collectors.toList());
        List<float[]> embeddings = embeddingService.embedBatch(texts);

        // Assign embeddings back to chunks
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(embeddings.get(i));
        }
        log.info("Embeddings generated successfully");

        // ── Stage 5: Store ─────────────────────────────────────────────────
        log.info("Stage 5: Storing in pgvector...");
        String contentType = tikaExtractorService.detectContentType(file);
        String fileType = contentType.contains("pdf") ? "pdf" : "docx";

        Long sourceId = vectorStoreService.saveDocumentSource(filename, fileType, chunks.size());
        vectorStoreService.saveChunks(sourceId, chunks);
        log.info("Document stored with sourceId={}", sourceId);

        // ── Return summary ─────────────────────────────────────────────────
        long tableChunks = chunks.stream()
                .filter(c -> c.getChunkType().name().equals("TABLE")).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId",        sourceId);
        result.put("filename",        filename);
        result.put("totalChunks",     chunks.size());
        result.put("tablesDetected",  tableChunks);
        result.put("narrativeChunks", chunks.size() - tableChunks);
        result.put("status",          "success");
        result.put("message",
                String.format("Successfully processed '%s': %d chunks (%d table, %d narrative)",
                        filename, chunks.size(), tableChunks, chunks.size() - tableChunks));

        return result;
    }
}

