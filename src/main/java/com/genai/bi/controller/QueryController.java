package com.genai.bi.controller;

import com.genai.bi.dto.QueryResponse;
import com.genai.bi.service.QueryService;
import com.genai.bi.service.RAGService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Query Controller
 *
 * Base Path:
 *   /api/query
 *
 * Handles:
 *   - SQL queries
 *   - RAG queries
 *   - Automatic routing
 */

@RestController
@RequestMapping("/api/query")
@CrossOrigin(origins = "*")
public class QueryController {

    private final QueryService queryService;
    private final RAGService ragService;

    public QueryController(QueryService queryService, RAGService ragService) {
        this.queryService = queryService;
        this.ragService = ragService;
    }

    // ─────────────────────────────────────────────
    // Unified Query Endpoint
    // ─────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> query(@RequestBody Map<String,Object> request) {

        try {

            String question = (String) request.get("question");

            if(question == null || question.trim().isEmpty()){
                return ResponseEntity.badRequest().body(Map.of(
                        "status","error",
                        "message","Question is required"
                ));
            }

            String queryType = ragService.classifyQuery(question.trim());

            if("RAG".equals(queryType)){

                Long sourceId = request.get("sourceId") != null
                        ? Long.valueOf(request.get("sourceId").toString())
                        : null;

                Map<String,Object> ragResult =
                        ragService.query(question.trim(), sourceId);

                return ResponseEntity.ok(ragResult);

            }else{

                QueryResponse sqlResult =
                        queryService.processQuery(question.trim());

                return ResponseEntity.ok(sqlResult);
            }

        }catch(Exception e){

            return ResponseEntity.internalServerError().body(Map.of(
                    "status","error",
                    "message","Query failed: " + e.getMessage()
            ));
        }
    }
}