package com.genai.bi.service;

import org.springframework.beans.factory.annotation.Value;

import okhttp3.*;
import com.google.gson.*;
import org.springframework.stereotype.Service;

@Service
public class LlmService {

    private final SchemaService schemaService;

    @Value("${openrouter.api.key}")
    private String apiKey;
    public LlmService(SchemaService schemaService){
        this.schemaService = schemaService;
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();


    // -----------------------------
    // Extract JSON safely from LLM
    // -----------------------------
    private String extractJson(String text){

        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");

        if(start == -1 || end == -1){
            throw new RuntimeException("No JSON found in LLM response: " + text);
        }

        return text.substring(start, end + 1);
    }


    // -----------------------------
    // Generic LLM caller
    // -----------------------------
    private String callLLM(String prompt) throws Exception {

        JsonObject message = new JsonObject();
        message.addProperty("role","user");
        message.addProperty("content",prompt);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model","meta-llama/llama-3.1-8b-instruct");
        body.add("messages",messages);

        Request request = new Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization","Bearer " + apiKey)
                .addHeader("Content-Type","application/json")
                .post(RequestBody.create(
                        body.toString(),
                        MediaType.parse("application/json")
                ))
                .build();

        Response response = client.newCall(request).execute();

        String responseBody = response.body().string();

        System.out.println("Raw LLM Response:\n" + responseBody);

        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

        String content = json.getAsJsonArray("choices")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("message")
                .get("content")
                .getAsString();

        return content.trim();
    }


    // -----------------------------
    // STEP 1: Generate SQL
    // -----------------------------
    public String generateSql(String question) throws Exception {

        String schema = schemaService.getDatabaseSchema();

        String prompt = """
You are a PostgreSQL expert.

Database Schema:
%s

Task:
Convert the user question into PostgreSQL SQL.

Rules:
1. Use ONLY tables and columns from schema.
2. Use SUM() for numeric aggregations.
3. SQL must be PostgreSQL compatible.
4. Do NOT generate JSON SQL functions such as:
   json_agg, json_build_object, row_to_json.
5. Only generate simple SELECT queries.

Return ONLY valid JSON.

DO NOT include:
- explanations
- markdown
- code blocks
- comments

Example:

{
 "sql": "SELECT region, SUM(revenue) AS revenue_share FROM sales GROUP BY region"
}

User Question:
%s
""".formatted(schema, question);

        String response = callLLM(prompt);

        response = extractJson(response);

        JsonObject json = JsonParser.parseString(response).getAsJsonObject();

        return json.get("sql").getAsString();
    }


    // -----------------------------
    // STEP 2: Chart + Insight
    // -----------------------------
    public JsonObject generateChartAndInsight(String question, String dataJson) throws Exception {

        String prompt = """
You are a Business Intelligence assistant.

User Question:
%s

Query Result Data:
%s

Task:
1. Suggest the best chart type
2. Identify xAxis and yAxis
3. Write detailed 2-3 insights based on the data as JSON array of strings

Chart types allowed:
bar, pie, line, scatter, table

Return ONLY valid JSON.

Example:

{
 "chartType":"bar",
 "xAxis":"region",
 "yAxis":"revenue",
 "insight":"America generates the highest revenue..."
}

Do NOT include markdown or explanations.
""".formatted(question, dataJson);

        String response = callLLM(prompt);

        response = extractJson(response);

        return JsonParser.parseString(response).getAsJsonObject();
    }

}