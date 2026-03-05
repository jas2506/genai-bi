package com.genai.bi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Stage 6 + 7: RAG Query Pipeline
 *
 * Full pipeline at query time:
 *   1. Embed the user's question  (OpenRouter)
 *   2. Similarity search          (pgvector)
 *   3. Assemble context prompt
 *   4. Call Groq for the answer   (llama3-70b-8192)
 *   5. Return answer + source citations
 *
 * ── Prompt Design for Financial 10-K Questions ───────────────────────────────
 *   The system prompt instructs the LLM to:
 *   - Cite specific numbers from context
 *   - Express $ amounts with units (millions/billions)
 *   - Calculate comparisons (YoY growth) when asked
 *   - Say "not found in report" if data isn't in context
 *
 * ── Context Assembly ─────────────────────────────────────────────────────────
 *   Top-K chunks are assembled as numbered context blocks:
 *
 *   [Context 1 | Section: CONSOLIDATED STATEMENTS OF INCOME | Type: table]
 *   Net revenues was 52,931 in 2024, 49,282 in 2023, and 43,151 in 2022.
 *
 *   [Context 2 | Section: MANAGEMENT'S DISCUSSION | Type: narrative]
 *   Revenue growth was driven by strong Asia-Pacific performance...
 */
@Service
public class RAGService {

    private final SchemaService schemaService;

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;


    @Value("${groq.api.key}")
    private String groqApiKey;

    @Value("${groq.chat.url}")
    private String groqChatUrl;

    @Value("${groq.chat.model}")
    private String groqChatModel;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public RAGService(SchemaService schemaService, EmbeddingService embeddingService, VectorStoreService vectorStoreService) {
        this.schemaService = schemaService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
    }

    /**
     * Main RAG query pipeline.
     *
     * @param question  User's natural language question
     * @param sourceId  Optional: restrict search to one document
     * @return          Map with "answer", "sources", "queryType"
     */
    public Map<String, Object> query(String question, Long sourceId) throws Exception {

        // ── Step 1: Embed the question ─────────────────────────────────────
        float[] questionEmbedding = embeddingService.embedText(question);

        // ── Step 2: Similarity search in pgvector ──────────────────────────
        List<Map<String, Object>> retrievedChunks =
                vectorStoreService.similaritySearch(questionEmbedding, sourceId);

        if (retrievedChunks.isEmpty()) {
            return Map.of(
                    "answer", "No relevant information was found in the uploaded documents for your question.",
                    "sources", List.of(),
                    "queryType", "RAG"
            );
        }

        // ── Step 3: Assemble context ───────────────────────────────────────
        String context = buildContext(retrievedChunks);

        // ── Step 4: Call Groq LLM with context ────────────────────────────
        String answer = callGroqWithRAGPrompt(question, context);

        // ── Step 5: Build source citations for UI ─────────────────────────
        List<Map<String, Object>> sources = retrievedChunks.stream()
                .map(chunk -> {
                    Map<String, Object> source = new LinkedHashMap<>();
                    // Show raw_content (original table/text) in citations
                    String displayText = (String) chunk.getOrDefault("raw_content", chunk.get("content"));
                    // Truncate long raw tables for UI display
                    if (displayText != null && displayText.length() > 500) {
                        displayText = displayText.substring(0, 500) + "...";
                    }
                    source.put("content",      displayText);
                    source.put("sectionTitle", chunk.get("section_title"));
                    source.put("chunkType",    chunk.get("chunk_type"));
                    source.put("similarity",   Math.round((Double) chunk.get("similarity") * 100) / 100.0);
                    return source;
                })
                .collect(Collectors.toList());

        return Map.of(
                "answer",    answer,
                "sources",   sources,
                "queryType", "RAG"
        );
    }

    // ── Context Assembly ───────────────────────────────────────────────────────

    /**
     * Builds the context string from retrieved chunks.
     * Each chunk is labeled with its section and type for LLM clarity.
     */
    private String buildContext(List<Map<String, Object>> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> chunk = chunks.get(i);
            String section = chunk.get("section_title") != null
                    ? (String) chunk.get("section_title")
                    : "General";
            String type = (String) chunk.getOrDefault("chunk_type", "narrative");

            sb.append("[Context ").append(i + 1)
                    .append(" | Section: ").append(section)
                    .append(" | Type: ").append(type).append("]\n");
            sb.append(chunk.get("content")).append("\n\n");
        }
        return sb.toString().trim();
    }

    // ── Groq LLM Call ──────────────────────────────────────────────────────────

    /**
     * Calls Groq's chat completion API with the RAG prompt.
     *
     * System prompt is tuned for financial 10-K analysis:
     *   - Forces use of specific numbers from context
     *   - Handles YoY calculations
     *   - Refuses to hallucinate data not in context
     */
    private String callGroqWithRAGPrompt(String question, String context) throws Exception {

        String systemPrompt =
                "You are a financial analyst assistant helping users analyze a company's annual report (10-K). " +
                        "Answer the user's question using ONLY the information provided in the context below. " +
                        "Rules:\n" +
                        "1. Always cite specific numbers from the context.\n" +
                        "2. If amounts are in millions, say so (e.g., '$52,931 million').\n" +
                        "3. If asked for growth or comparison, calculate and show the percentage.\n" +
                        "4. If the context contains table data, reference row labels and column years precisely.\n" +
                        "5. If the answer is NOT in the context, say: 'This information was not found in the provided report sections.'\n" +
                        "6. Do NOT make up numbers or reference general knowledge — use only the context.";

        String userMessage =
                "CONTEXT FROM ANNUAL REPORT:\n" +
                        "──────────────────────────\n" +
                        context +
                        "\n──────────────────────────\n\n" +
                        "QUESTION: " + question;

        // Build Groq API request (OpenAI-compatible format)
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", groqChatModel);
        requestBody.put("max_tokens", 1024);
        requestBody.put("temperature", 0.1);   // low temp for factual financial answers
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userMessage)
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(groqApiKey);

        HttpEntity<String> request = new HttpEntity<>(
                objectMapper.writeValueAsString(requestBody),
                headers
        );

        ResponseEntity<String> response = restTemplate.exchange(
                groqChatUrl,
                HttpMethod.POST,
                request,
                String.class
        );

        if (response.getStatusCode() != HttpStatus.OK) {
            throw new RuntimeException("Groq API error: " + response.getStatusCode());
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        return root.path("choices").get(0).path("message").path("content").asText();
    }

    // ── Query Routing Helper ──────────────────────────────────────────────────

    /**
     * Asks Groq to classify the question as SQL or RAG.
     * Called by the QueryController to route questions.
     *
     * Returns "SQL" for questions about structured CSV/Excel data.
     * Returns "RAG" for questions about documents/reports.
     */
    public String classifyQuery(String question) throws Exception {

        String schema = schemaService.getDatabaseSchema();

        String prompt = """
You are a query router for a business intelligence platform.

The system has two data sources.

1) SQL
- Structured datasets from CSV/Excel stored in PostgreSQL
- Used for aggregations, comparisons, charts

Available SQL tables:
%s

2) RAG
- Uploaded PDF or DOCX reports
- Used for narrative answers and document knowledge

Task:
Classify the user question.

Rules:
- Questions about dataset metrics, comparisons, totals → SQL
- Questions about reports, explanations, financial statements → RAG

Return ONLY valid JSON.

Format:
{
 "route": "SQL"
}

or

{
 "route": "RAG"
}

DO NOT answer the question.
DO NOT explain anything.

Question:
%s
""".formatted(schema, question);


        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", groqChatModel);
        requestBody.put("max_tokens", 50);
        requestBody.put("temperature", 0.0);
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(groqApiKey);

        HttpEntity<String> request = new HttpEntity<>(
                objectMapper.writeValueAsString(requestBody),
                headers
        );

        ResponseEntity<String> response = restTemplate.exchange(
                groqChatUrl, HttpMethod.POST, request, String.class
        );

        JsonNode root = objectMapper.readTree(response.getBody());

        String content = root.path("choices")
                .get(0)
                .path("message")
                .path("content")
                .asText();

        // Extract JSON from response safely
        int start = content.indexOf("{");
        int end = content.lastIndexOf("}");

        if(start == -1 || end == -1){
            throw new RuntimeException("Router returned invalid JSON: " + content);
        }

        String json = content.substring(start, end + 1);

        JsonNode parsed = objectMapper.readTree(json);

        return parsed.get("route").asText().toUpperCase();
    }



}
