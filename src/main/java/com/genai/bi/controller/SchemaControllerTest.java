package com.genai.bi.controller;

import com.genai.bi.service.SchemaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SchemaControllerTest {

    private final SchemaService schemaService;

    public SchemaControllerTest(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @GetMapping("/api/schema")
    public String getSchema() {
        return schemaService.getDatabaseSchema();
    }
}