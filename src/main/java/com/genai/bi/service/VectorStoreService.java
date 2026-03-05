package com.genai.bi.service;


import com.genai.bi.model.DocumentChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.*;

/**
 * Stage 5 (Upload) + Stage 6 (Query): pgvector Storage and Similarity Search
 *
 * Uses Spring JDBC directly (not JPA) because pgvector's VECTOR type
 * doesn't map cleanly with standard JPA column types.
 *
 * ── Insert Strategy ──────────────────────────────────────────────────────────
 *   Uses JDBC batch insert for performance.
 *   The vector is passed as a String "[0.1,0.2,...]" and cast with ::vector in SQL.
 *   Types.OTHER tells JDBC: "don't convert this, let PostgreSQL handle it."
 *
 * ── Similarity Search ────────────────────────────────────────────────────────
 *   Uses pgvector's cosine distance operator <=>
 *   Lower distance = more similar, so we ORDER BY distance ASC.
 *   We return similarity = 1 - distance (0=unrelated, 1=identical).
 *
 *   SQL:
 *     SELECT *, 1 - (embedding <=> ?::vector) AS similarity
 *     FROM document_chunks
 *     ORDER BY embedding <=> ?::vector
 *     LIMIT 5;
 */
@Service
public class VectorStoreService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${rag.retrieval.topk:5}")
    private int topK;

    public VectorStoreService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Upload: Save source metadata ───────────────────────────────────────────

    /**
     * Creates a document_sources record and returns the generated ID.
     */
    @Transactional
    public Long saveDocumentSource(String filename, String fileType, int totalChunks) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO document_sources (filename, file_type, total_chunks) " +
                        "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                filename, fileType, totalChunks
        );
    }

    // ── Upload: Batch insert chunks with embeddings ────────────────────────────

    /**
     * Batch-inserts all document chunks with their embeddings into pgvector.
     *
     * @param sourceId  The document_sources.id
     * @param chunks    List of DocumentChunk objects (embeddings must be populated)
     */
    @Transactional
    public void saveChunks(Long sourceId, List<DocumentChunk> chunks) {
        String sql =
                "INSERT INTO document_chunks " +
                        "  (source_id, chunk_index, chunk_type, content, raw_content, section_title, embedding) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::vector)";

        // Use batch update for performance
        jdbcTemplate.batchUpdate(sql, chunks, 50, (ps, chunk) -> {
            ps.setLong(1, sourceId);
            ps.setInt(2, chunk.getChunkIndex());
            ps.setString(3, chunk.getChunkType().name().toLowerCase());
            ps.setString(4, chunk.getContent());
            ps.setString(5, chunk.getRawContent() != null ? chunk.getRawContent() : chunk.getContent());
            ps.setString(6, chunk.getSectionTitle());
            // Pass vector string with Types.OTHER → PostgreSQL handles ::vector cast
            ps.setObject(7, EmbeddingService.toVectorString(chunk.getEmbedding()), Types.OTHER);
        });
    }

    // ── Query: Similarity search ───────────────────────────────────────────────

    /**
     * Finds the top-K most similar chunks to a query embedding.
     * Optionally filters to a specific document source.
     *
     * @param queryEmbedding  float[1536] vector from EmbeddingService.embedText()
     * @param sourceId        Optional: filter to specific document (null = search all)
     * @return                List of maps: {content, raw_content, chunk_type, section_title, similarity}
     */
    public List<Map<String, Object>> similaritySearch(float[] queryEmbedding, Long sourceId) {
        String vectorStr = EmbeddingService.toVectorString(queryEmbedding);

        String sql;
        Object[] params;

        if (sourceId != null) {
            // Search within a specific document
            sql = "SELECT content, raw_content, chunk_type, section_title, " +
                    "       1 - (embedding <=> ?::vector) AS similarity " +
                    "FROM document_chunks " +
                    "WHERE source_id = ? " +
                    "ORDER BY embedding <=> ?::vector " +
                    "LIMIT ?";
            params = new Object[]{vectorStr, sourceId, vectorStr, topK};
        } else {
            // Search across all documents
            sql = "SELECT content, raw_content, chunk_type, section_title, " +
                    "       1 - (embedding <=> ?::vector) AS similarity " +
                    "FROM document_chunks " +
                    "ORDER BY embedding <=> ?::vector " +
                    "LIMIT ?";
            params = new Object[]{vectorStr, vectorStr, topK};
        }

        // Use PreparedStatement to handle the duplicate vector parameter correctly
        return jdbcTemplate.query(
                sql,
                ps -> {
                    for (int i = 0; i < params.length; i++) {
                        if (params[i] instanceof String && ((String) params[i]).startsWith("[")) {
                            ps.setObject(i + 1, params[i], Types.OTHER);
                        } else if (params[i] instanceof Long) {
                            ps.setLong(i + 1, (Long) params[i]);
                        } else if (params[i] instanceof Integer) {
                            ps.setInt(i + 1, (Integer) params[i]);
                        }
                    }
                },
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("content",       rs.getString("content"));
                    row.put("raw_content",   rs.getString("raw_content"));
                    row.put("chunk_type",    rs.getString("chunk_type"));
                    row.put("section_title", rs.getString("section_title"));
                    row.put("similarity",    rs.getDouble("similarity"));
                    return row;
                }
        );
    }

    // ── Utility ────────────────────────────────────────────────────────────────

    /**
     * Returns all document sources (for the frontend file list dropdown).
     */
    public List<Map<String, Object>> getAllSources() {
        return jdbcTemplate.queryForList(
                "SELECT id, filename, file_type, total_chunks, upload_time " +
                        "FROM document_sources ORDER BY upload_time DESC"
        );
    }

    /**
     * Deletes a document and all its chunks (via CASCADE).
     */
    @Transactional
    public void deleteSource(Long sourceId) {
        jdbcTemplate.update("DELETE FROM document_sources WHERE id = ?", sourceId);
    }
}
