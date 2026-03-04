package com.genai.bi.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SchemaService {

    private final JdbcTemplate jdbcTemplate;

    public SchemaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String getDatabaseSchema() {

        StringBuilder schemaBuilder = new StringBuilder();

        schemaBuilder.append("Database Schema:\n\n");

        // Fetch all table names
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );

        for (String table : tables) {

            // Fetch column names for each table
            List<String> columns = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                    String.class,
                    table
            );

            schemaBuilder.append(table)
                    .append("(")
                    .append(String.join(", ", columns))
                    .append(")")
                    .append("\n");
        }

        return schemaBuilder.toString();
    }
}