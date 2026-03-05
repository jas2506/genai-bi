package com.genai.bi.controller;

import com.genai.bi.service.DocumentService;
import com.genai.bi.service.RAGService;
import com.genai.bi.service.VectorStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * RAG Controller
 *
 * Base Path:
 *   /api/rag
 *
 * Endpoints:
 *   POST   /api/rag/upload-document
 *   POST   /api/rag/query-document
 *   GET    /api/rag/documents
 *   DELETE /api/rag/documents/{id}
 */

@RestController
@RequestMapping("/api/rag")
@CrossOrigin(origins = "*")
public class RagController {

    private final DocumentService documentService;
    private final RAGService ragService;
    private final VectorStoreService vectorStoreService;

    public RagController(
            DocumentService documentService,
            RAGService ragService,
            VectorStoreService vectorStoreService
    ) {
        this.documentService = documentService;
        this.ragService = ragService;
        this.vectorStoreService = vectorStoreService;
    }

    // ─────────────────────────────────────────────
    // Upload PDF / DOCX
    // ─────────────────────────────────────────────

    @PostMapping("/upload-document")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file
    ) {
        try {

            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "File is empty"
                ));
            }

            String filename = file.getOriginalFilename();

            if (filename == null ||
                    (!filename.toLowerCase().endsWith(".pdf")
                            && !filename.toLowerCase().endsWith(".docx"))) {

                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "Only PDF and DOCX files are supported"
                ));
            }

            Map<String, Object> result = documentService.processDocument(file);

            return ResponseEntity.ok(result);

        } catch (Exception e) {

            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to process document: " + e.getMessage()
            ));
        }
    }

    // ─────────────────────────────────────────────
    // Direct RAG Query
    // ─────────────────────────────────────────────

    @PostMapping("/query-document")
    public ResponseEntity<Map<String, Object>> queryDocument(
            @RequestBody Map<String, Object> request
    ) {

        try {

            String question = (String) request.get("question");

            if (question == null || question.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "Question is required"
                ));
            }

            Long sourceId = request.get("sourceId") != null
                    ? Long.valueOf(request.get("sourceId").toString())
                    : null;

            Map<String, Object> result =
                    ragService.query(question.trim(), sourceId);

            return ResponseEntity.ok(result);

        } catch (Exception e) {

            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "RAG query failed: " + e.getMessage()
            ));
        }
    }

    // ─────────────────────────────────────────────
    // List uploaded documents
    // ─────────────────────────────────────────────

    @GetMapping("/documents")
    public ResponseEntity<?> listDocuments() {
        return ResponseEntity.ok(vectorStoreService.getAllSources());
    }

    // ─────────────────────────────────────────────
    // Delete document
    // ─────────────────────────────────────────────

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @PathVariable Long id
    ) {

        vectorStoreService.deleteSource(id);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Document deleted"
        ));
    }
}