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

            String tableName = file.getOriginalFilename()
                    .replace(".csv", "")
                    .replaceAll("[^a-zA-Z0-9]", "_")
                    .toLowerCase();

            String headerLine = reader.readLine();

            List<String> headers = Arrays.stream(headerLine.split(","))
                    .map(h -> h.trim()
                            .replaceAll("[^a-zA-Z0-9]", "_")
                            .toLowerCase())
                    .collect(Collectors.toList());

            // 🔹 Read first data row to detect column types
            String firstDataLine = reader.readLine();

            if (firstDataLine == null) {
                throw new RuntimeException("CSV contains no data rows");
            }

            List<String> firstRow = Arrays.stream(firstDataLine.split(",", -1))
                    .map(String::trim)
                    .collect(Collectors.toList());

            // 🔹 Detect and store column types so we can use them for parsing later
            List<String> columnTypes = detectColumnTypes(firstRow);

            // 🔹 Create table using detected types
            createTable(headers, columnTypes, tableName);

            // 🔹 Insert first row (parsed into proper data types)
            insertRow(headers, firstRow, columnTypes, tableName);

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.trim().isEmpty())
                    continue;

                List<String> values = Arrays.stream(line.split(",", -1))
                        .map(String::trim)
                        .collect(Collectors.toList());

                if (values.size() != headers.size()) {
                    System.out.println("Skipping malformed row: " + line);
                    continue;
                }

                // 🔹 Insert subsequent rows (parsed into proper data types)
                insertRow(headers, values, columnTypes, tableName);
            }

        } catch (Exception e) {
            throw new RuntimeException("Error processing CSV", e);
        }
    }

    /**
     * Helper method to determine the SQL data type based on the values in the sample row.
     */
    private List<String> detectColumnTypes(List<String> sampleRow) {
        return sampleRow.stream().map(value -> {
            if (value.matches("-?\\d+")) {
                return "INTEGER"; // Use BIGINT if you expect very large numbers
            } else if (value.matches("-?\\d+\\.\\d+")) {
                return "DOUBLE PRECISION";
            }
            return "TEXT";
        }).collect(Collectors.toList());
    }

    private void createTable(List<String> headers, List<String> types, String tableName) {

        StringBuilder sql = new StringBuilder();

        sql.append("CREATE TABLE IF NOT EXISTS ")
                .append(tableName)
                .append(" (");

        for (int i = 0; i < headers.size(); i++) {
            sql.append(headers.get(i))
                    .append(" ")
                    .append(types.get(i))
                    .append(",");
        }

        sql.deleteCharAt(sql.length() - 1);
        sql.append(")");

        jdbcTemplate.execute(sql.toString());
    }

    private void insertRow(List<String> headers, List<String> values, List<String> types, String tableName) {

        String columns = String.join(",", headers);

        String placeholders = headers.stream()
                .map(h -> "?")
                .collect(Collectors.joining(","));

        String sql = "INSERT INTO " + tableName +
                " (" + columns + ") VALUES (" + placeholders + ")";

        // 🔹 Create an array of Objects to hold properly casted values
        Object[] parsedValues = new Object[values.size()];

        for (int i = 0; i < values.size(); i++) {
            String val = values.get(i);
            String type = types.get(i);

            if (val == null || val.isEmpty()) {
                parsedValues[i] = null;
                continue;
            }

            try {
                // Convert string to the correct Java object based on detected SQL type
                switch (type) {
                    case "INTEGER":
                        parsedValues[i] = Long.parseLong(val); // Using Long prevents overflow for large numbers
                        break;
                    case "DOUBLE PRECISION":
                        parsedValues[i] = Double.parseDouble(val);
                        break;
                    default:
                        parsedValues[i] = val; // TEXT stays as String
                }
            } catch (NumberFormatException e) {
                // Fallback in case a later row violates the first row's detected type pattern
                parsedValues[i] = val;
            }
        }

        jdbcTemplate.update(sql, parsedValues);
    }
}