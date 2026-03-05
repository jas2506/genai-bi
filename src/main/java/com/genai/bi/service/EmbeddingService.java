package com.genai.bi.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Stage 4: Embedding Generation via OpenRouter
 *
 * Uses OpenRouter's embedding endpoint which proxies OpenAI's text-embedding-3-small.
 *   Model:      openai/text-embedding-3-small
 *   Dimensions: 1536
 *   Cost:       ~$0.02 per 1M tokens (a full 10-K = ~100K tokens = $0.002)
 *
 * API Reference: https://openrouter.ai/api/v1/embeddings
 * Compatible with OpenAI embedding API format.
 *
 * ── Batching Strategy ────────────────────────────────────────────────────────
 *   We batch chunks in groups of 20 to avoid rate limits.
 *   For a 100-page 10-K with ~80 chunks, this is 4 API calls total.
 *
 * ── Why not use Groq for embeddings? ─────────────────────────────────────────
 *   Groq's free tier only supports chat/completion models (llama3, mixtral).
 *   It does NOT provide an embeddings endpoint. OpenRouter fills this gap.
 */
@Service
public class EmbeddingService {

    @Value("${openrouter.api.key}")
    private String apiKey;

    @Value("${openrouter.embedding.url}")
    private String embeddingUrl;

    @Value("${openrouter.embedding.model}")
    private String embeddingModel;

    private static final int BATCH_SIZE = 20;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Generates embeddings for a single text string.
     * Used at query time to embed the user's question.
     *
     * @param text  The question or text to embed
     * @return      float[1536] embedding vector
     */
    public float[] embedText(String text) throws Exception {
        List<float[]> results = embedBatch(List.of(text));
        return results.get(0);
    }

    /**
     * Generates embeddings for a list of chunks.
     * Batches requests to stay within rate limits.
     *
     * @param texts  List of text strings to embed
     * @return       List of float[1536] vectors, same order as input
     */
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        List<float[]> allEmbeddings = new ArrayList<>();

        // Process in batches of BATCH_SIZE
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, end);
            List<float[]> batchEmbeddings = callEmbeddingAPI(batch);
            allEmbeddings.addAll(batchEmbeddings);

            // Small delay between batches to respect rate limits
            if (end < texts.size()) {
                Thread.sleep(200);
            }
        }

        return allEmbeddings;
    }

    /**
     * Makes a single batched call to the OpenRouter embeddings API.
     *
     * Request format:
     * {
     *   "model": "openai/text-embedding-3-small",
     *   "input": ["text1", "text2", ...]
     * }
     *
     * Response format:
     * {
     *   "data": [
     *     { "index": 0, "embedding": [0.023, -0.41, ...] },
     *     { "index": 1, "embedding": [...] }
     *   ]
     * }
     */
    private List<float[]> callEmbeddingAPI(List<String> texts) throws Exception {
        // Build request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", embeddingModel);
        requestBody.put("input", texts);

        // Build headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("HTTP-Referer", "https://bi-platform.hackathon.dev");  // OpenRouter requires this
        headers.set("X-Title", "BI Platform RAG");

        HttpEntity<String> request = new HttpEntity<>(
                objectMapper.writeValueAsString(requestBody),
                headers
        );

        // Call API
        ResponseEntity<String> response = restTemplate.exchange(
                embeddingUrl,
                HttpMethod.POST,
                request,
                String.class
        );

        if (response.getStatusCode() != HttpStatus.OK) {
            throw new RuntimeException("Embedding API error: " + response.getStatusCode() + " — " + response.getBody());
        }

        // Parse response
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode dataArray = root.path("data");

        if (dataArray.isMissingNode() || !dataArray.isArray()) {
            throw new RuntimeException("Unexpected embedding API response: " + response.getBody());
        }

        // Build result in correct order (API returns sorted by index)
        float[][] ordered = new float[texts.size()][];
        for (JsonNode item : dataArray) {
            int index = item.path("index").asInt();
            JsonNode embeddingNode = item.path("embedding");
            float[] vector = new float[embeddingNode.size()];
            for (int j = 0; j < embeddingNode.size(); j++) {
                vector[j] = (float) embeddingNode.get(j).asDouble();
            }
            ordered[index] = vector;
        }

        return Arrays.asList(ordered);
    }

    /**
     * Converts a float[] embedding to the pgvector string format.
     * pgvector expects: [0.023,-0.410,0.887,...]
     *
     * This is used by VectorStoreService when inserting/querying.
     */
    public static String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", embedding[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}

