package com.genai.bi.service;


import com.genai.bi.model.ChunkType;
import com.genai.bi.model.DocumentChunk;
import com.genai.bi.model.TextBlock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * Stage 3: Chunking
 *
 * Two strategies based on block type:
 *
 * ── NARRATIVE BLOCKS → Sliding Window ───────────────────────────────────────
 *   Split prose into overlapping word windows.
 *   Default: 500 words per chunk, 50 word overlap.
 *   Overlap ensures sentences that span chunk boundaries aren't lost.
 *
 *   Example (simplified):
 *     Chunk 1: words[0..499]
 *     Chunk 2: words[450..949]   ← 50-word overlap
 *     Chunk 3: words[900..1399]
 *
 * ── TABLE BLOCKS → Table-to-Natural-Language ────────────────────────────────
 *   Each financial table becomes ONE chunk.
 *   The table rows are converted to natural language sentences.
 *
 *   Why NL conversion? Embedding models are trained on language, not aligned
 *   numbers. "Net revenues was $52,931M in 2024" embeds 10x better than
 *   "Net revenues  52,931  49,282  43,151"
 *
 *   Input table row:  "Net revenues   52,931   49,282   43,151"
 *   Column headers:   ["2024", "2023", "2022"]
 *   Output sentence:  "Net revenues was 52,931 in 2024, 49,282 in 2023, and 43,151 in 2022."
 *
 *   The raw table text is preserved separately for display in the UI.
 */
@Service
public class TextChunkerService {

    @Value("${rag.chunk.size:500}")
    private int chunkSize;

    @Value("${rag.chunk.overlap:50}")
    private int chunkOverlap;

    // Matches a financial number (same pattern as classifier)
    private static final Pattern FINANCIAL_NUMBER = Pattern.compile(
            "\\(?\\$?\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?\\)?%?"
    );

    /**
     * Main entry: takes classified blocks, returns chunks ready for embedding.
     */
    public List<DocumentChunk> chunkBlocks(List<TextBlock> blocks) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int globalIndex = 0;

        for (TextBlock block : blocks) {
            if (block.getType() == ChunkType.HEADER) {
                // Headers are tiny — skip as standalone chunks (they're attached via sectionTitle)
                continue;
            }

            if (block.getType() == ChunkType.TABLE) {
                // One chunk per table
                DocumentChunk chunk = buildTableChunk(block, globalIndex++);
                chunks.add(chunk);

            } else {
                // NARRATIVE: sliding window
                List<DocumentChunk> narrativeChunks = buildNarrativeChunks(block, globalIndex);
                globalIndex += narrativeChunks.size();
                chunks.addAll(narrativeChunks);
            }
        }

        return chunks;
    }

    // ── TABLE CHUNK ─────────────────────────────────────────────────────────────

    private DocumentChunk buildTableChunk(TextBlock block, int index) {
        String nlContent = convertTableToNaturalLanguage(block);
        String sectionContext = block.getSectionTitle() != null
                ? "From section '" + block.getSectionTitle() + "': "
                : "";

        return DocumentChunk.builder()
                .chunkIndex(index)
                .chunkType(ChunkType.TABLE)
                .content(sectionContext + nlContent)   // NL version → gets embedded
                .rawContent(block.getText())            // Raw table → shown in UI citations
                .sectionTitle(block.getSectionTitle())
                .build();
    }

    /**
     * Converts a financial table to natural language sentences.
     *
     * Input:
     *   sectionTitle: "CONSOLIDATED STATEMENTS OF INCOME"
     *   columnHeaders: ["2024", "2023", "2022"]
     *   text:
     *     "Net revenues          52,931   49,282   43,151
     *      Cost of revenues      20,117   19,176   17,108
     *      Gross profit          32,814   30,106   26,043"
     *
     * Output:
     *   "Financial data from CONSOLIDATED STATEMENTS OF INCOME.
     *    Net revenues was 52,931 in 2024, 49,282 in 2023, and 43,151 in 2022.
     *    Cost of revenues was 20,117 in 2024, 19,176 in 2023, and 17,108 in 2022.
     *    Gross profit was 32,814 in 2024, 30,106 in 2023, and 26,043 in 2022."
     */
    private String convertTableToNaturalLanguage(TextBlock block) {
        String[] lines = block.getText().split("\n");
        String[] headers = block.getColumnHeaders();
        StringBuilder sb = new StringBuilder();

        // Section intro sentence
        if (block.getSectionTitle() != null && !block.getSectionTitle().isEmpty()) {
            sb.append("Financial data from ").append(block.getSectionTitle()).append(". ");
        }

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Skip lines that are purely year headers (no label prefix)
            if (isYearHeaderLine(line)) continue;

            // Parse: split by 2+ spaces to separate label from values
            String[] parts = line.split("\\s{2,}");
            if (parts.length < 2) continue;

            String label = parts[0].trim();
            if (label.isEmpty()) continue;

            // Extract numeric values from the rest of the line
            List<String> values = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String val = parts[i].trim();
                if (!val.isEmpty() && FINANCIAL_NUMBER.matcher(val).find()) {
                    values.add(val);
                }
            }

            if (values.isEmpty()) continue;

            // Build sentence: "Net revenues was 52,931 in 2024, 49,282 in 2023..."
            sb.append(label).append(" was ");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0 && i == values.size() - 1) {
                    sb.append(", and ");
                } else if (i > 0) {
                    sb.append(", ");
                }
                sb.append(values.get(i));
                // Attach column header if available
                if (headers != null && i < headers.length) {
                    sb.append(" in ").append(headers[i]);
                }
            }
            sb.append(". ");
        }

        return sb.toString().trim();
    }

    // ── NARRATIVE CHUNKS ────────────────────────────────────────────────────────

    private List<DocumentChunk> buildNarrativeChunks(TextBlock block, int startIndex) {
        String text = block.getText();
        String[] words = text.split("\\s+");
        List<DocumentChunk> chunks = new ArrayList<>();
        int index = startIndex;

        // Add section title prefix so retrieval matches section-level queries
        String prefix = block.getSectionTitle() != null
                ? "[" + block.getSectionTitle() + "] "
                : "";

        int i = 0;
        while (i < words.length) {
            int end = Math.min(i + chunkSize, words.length);
            String chunkText = prefix + String.join(" ", Arrays.copyOfRange(words, i, end));

            if (!chunkText.trim().isEmpty()) {
                chunks.add(DocumentChunk.builder()
                        .chunkIndex(index++)
                        .chunkType(ChunkType.NARRATIVE)
                        .content(chunkText)
                        .rawContent(chunkText)       // for narrative, content = rawContent
                        .sectionTitle(block.getSectionTitle())
                        .build());
            }

            if (end == words.length) break;
            i += (chunkSize - chunkOverlap);  // slide forward with overlap
        }

        return chunks;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private boolean isYearHeaderLine(String line) {
        // Line contains ONLY years and whitespace (e.g., "     2024      2023      2022")
        String cleaned = line.trim().replaceAll("\\b20[12]\\d\\b", "").replaceAll("[\\s|]", "");
        return cleaned.isEmpty() && line.matches(".*\\b20[12]\\d\\b.*");
    }
}
