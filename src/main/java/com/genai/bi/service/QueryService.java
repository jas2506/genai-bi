package com.genai.bi.service;

import com.genai.bi.dto.QueryResponse;
import com.genai.bi.util.SqlValidator;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class QueryService {

    private final JdbcTemplate jdbcTemplate;
    private final LlmService llmService;

    public QueryService(JdbcTemplate jdbcTemplate, LlmService llmService){
        this.jdbcTemplate = jdbcTemplate;
        this.llmService = llmService;
    }

    public QueryResponse processQuery(String question) throws Exception {

        // STEP 1: Generate SQL
        String sql = llmService.generateSql(question);

        System.out.println("Generated SQL: " + sql);

        if(!SqlValidator.isSafe(sql)){
            throw new RuntimeException("Unsafe SQL generated");
        }

        // STEP 2: Execute SQL
        List<Map<String,Object>> results = jdbcTemplate.queryForList(sql);

        String dataJson = new Gson().toJson(results);

        // STEP 3: Generate chart + insight
        JsonObject chartPlan = llmService.generateChartAndInsight(question, dataJson);

        QueryResponse response = new QueryResponse();

        response.setQueryType("SQL");

        response.setChartType(chartPlan.get("chartType").getAsString());
        response.setXAxis(chartPlan.get("xAxis").getAsString());
        response.setYAxis(chartPlan.get("yAxis").getAsString());
        String insight = "";

        if(chartPlan.has("insight")){

            if(chartPlan.get("insight").isJsonArray()){

                JsonArray arr = chartPlan.getAsJsonArray("insight");

                StringBuilder builder = new StringBuilder();

                for(int i = 0; i < arr.size(); i++){
                    builder.append("- ")
                            .append(arr.get(i).getAsString())
                            .append("\n");
                }

                insight = builder.toString();

            } else {

                insight = chartPlan.get("insight").getAsString();
            }

        }
        else if(chartPlan.has("insights")){

            JsonArray arr = chartPlan.getAsJsonArray("insights");

            StringBuilder builder = new StringBuilder();

            for(int i = 0; i < arr.size(); i++){
                builder.append("- ")
                        .append(arr.get(i).getAsString())
                        .append("\n");
            }

            insight = builder.toString();
        }

        response.setInsight(insight);
        response.setData(results);

        return response;
    }
}