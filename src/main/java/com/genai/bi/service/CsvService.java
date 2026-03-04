package com.genai.bi.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CsvService {

    private final JdbcTemplate jdbcTemplate;

    public CsvService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void processCsv(MultipartFile file) {

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream()))) {

            String tableName = "uploaded_data";

            String headerLine = reader.readLine();
            List<String> headers = Arrays.stream(headerLine.split(","))
                    .map(h -> h.trim()
                            .replaceAll("[^a-zA-Z0-9]", "_")
                            .toLowerCase())
                    .collect(Collectors.toList());

            createTable(headers, tableName);

            String line;
            while ((line = reader.readLine()) != null) {
                // 1. Skip completely empty lines
                if (line.trim().isEmpty()) {
                    continue;
                }

                // 2. Add -1 to split() to preserve trailing empty columns
                List<String> values = Arrays.stream(line.split(",", -1))
                        .map(String::trim)
                        .collect(Collectors.toList());

                // 3. Safety check to prevent SQL argument mismatch errors
                if (values.size() != headers.size()) {
                    System.out.println("Skipping malformed row (column mismatch): " + line);
                    continue;
                }

                insertRow(headers, values, tableName);
            }

        } catch (Exception e) {
            throw new RuntimeException("Error processing CSV", e);
        }
    }

    private void createTable(List<String> headers, String tableName) {

        StringBuilder sql = new StringBuilder();

        sql.append("CREATE TABLE IF NOT EXISTS ")
                .append(tableName)
                .append(" (");

        for (String header : headers) {
            sql.append(header).append(" TEXT,");
        }

        sql.deleteCharAt(sql.length() - 1);
        sql.append(")");

        jdbcTemplate.execute(sql.toString());
    }

    private void insertRow(List<String> headers, List<String> values, String tableName) {

        String columns = String.join(",", headers);

        String placeholders = headers.stream()
                .map(h -> "?")
                .collect(Collectors.joining(","));

        String sql = "INSERT INTO " + tableName +
                " (" + columns + ") VALUES (" + placeholders + ")";

        jdbcTemplate.update(sql, values.toArray());
    }
}