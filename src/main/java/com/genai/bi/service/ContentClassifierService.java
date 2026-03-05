package com.genai.bi.service;


import com.genai.bi.model.ChunkType;
import com.genai.bi.model.TextBlock;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * Stage 2: Content Classification
 *
 * Processes raw Tika-extracted text and classifies each block as:
 *   HEADER    — section titles (ALL CAPS, short, no numbers)
 *   TABLE     — financial data rows (multiple numeric values per line)
 *   NARRATIVE — prose paragraphs (MD&A, risk factors, footnotes)
 *
 * ── Detection Rules for 10-K Tables ──────────────────────────────────────────
 *
 * A line is a TABLE ROW if it contains 2+ financial numbers.
 * Financial numbers in 10-K reports look like:
 *   52,931   (thousands/millions)
 *   $52,931  (with dollar sign)
 *   (1,234)  (negative in parentheses — accounting convention)
 *   18.5%    (percentages)
 *   2024     (fiscal years used as column headers)
 *
 * A TABLE BLOCK = 3+ consecutive table rows.
 * The line immediately before the first table row (if it contains years) = column headers.
 *
 * ── Detection Rules for Section Headers ──────────────────────────────────────
 *
 * A line is a SECTION HEADER if:
 *   - It's SHORT (< 120 chars)
 *   - It's ALL CAPS or Title Case with no numbers
 *   - Examples: "CONSOLIDATED STATEMENTS OF INCOME"
 *              "MANAGEMENT'S DISCUSSION AND ANALYSIS"
 */
@Service
public class ContentClassifierService {

    // Matches financial numbers: 52,931 | $52,931 | (1,234) | 18.5% | 2,024
    private static final Pattern FINANCIAL_NUMBER = Pattern.compile(
            "\\(?\\$?\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?\\)?%?"
    );

    // Matches fiscal year patterns: 2019-2030
    private static final Pattern FISCAL_YEAR = Pattern.compile("\\b20[12]\\d\\b|\\b19[89]\\d\\b");

    // Matches a section header: ALL CAPS words, may contain spaces, hyphens, apostrophes
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^[A-Z][A-Z\\s\\-'&,\\.\\(\\)]+$"
    );

    // Minimum consecutive table rows to be classified as a TABLE block
    private static final int MIN_TABLE_ROWS = 3;

    /**
     * Main entry point: takes raw Tika text, returns classified blocks.
     */
    public List<TextBlock> classify(String rawText) {
        String[] lines = rawText.split("\n");
        List<TextBlock> blocks = new ArrayList<>();

        String currentSectionTitle = "GENERAL";
        int i = 0;

        while (i < lines.length) {
            String line = lines[i].trim();

            // Skip empty lines
            if (line.isEmpty()) {
                i++;
                continue;
            }

            // ── Check: Is this a section header? ──────────────────────────
            if (isSectionHeader(line)) {
                currentSectionTitle = line;
                blocks.add(TextBlock.builder()
                        .type(ChunkType.HEADER)
                        .text(line)
                        .sectionTitle(line)
                        .startLine(i)
                        .endLine(i)
                        .build());
                i++;
                continue;
            }

            // ── Check: Is this the start of a table? ──────────────────────
            // Look ahead to see if we have MIN_TABLE_ROWS consecutive table rows
            int tableEnd = findTableEnd(lines, i);
            if (tableEnd - i >= MIN_TABLE_ROWS) {
                // Extract column headers: look for the year-header line
                // It's either the current line or the line before if it contains years
                String[] columnHeaders = extractColumnHeaders(lines, i);

                // Collect all table lines
                StringBuilder tableSb = new StringBuilder();
                for (int j = i; j <= tableEnd && j < lines.length; j++) {
                    if (!lines[j].trim().isEmpty()) {
                        tableSb.append(lines[j]).append("\n");
                    }
                }

                blocks.add(TextBlock.builder()
                        .type(ChunkType.TABLE)
                        .text(tableSb.toString().trim())
                        .sectionTitle(currentSectionTitle)
                        .columnHeaders(columnHeaders)
                        .startLine(i)
                        .endLine(tableEnd)
                        .build());

                i = tableEnd + 1;
                continue;
            }

            // ── Default: Narrative block ───────────────────────────────────
            // Collect consecutive non-header, non-table lines as one narrative block
            StringBuilder narrativeSb = new StringBuilder();
            int blockStart = i;
            while (i < lines.length) {
                String l = lines[i].trim();
                if (l.isEmpty()) { i++; continue; }
                if (isSectionHeader(l)) break;
                int nextTableEnd = findTableEnd(lines, i);
                if (nextTableEnd - i >= MIN_TABLE_ROWS) break;
                narrativeSb.append(l).append(" ");
                i++;
            }

            String narrativeText = narrativeSb.toString().trim();
            if (!narrativeText.isEmpty()) {
                blocks.add(TextBlock.builder()
                        .type(ChunkType.NARRATIVE)
                        .text(narrativeText)
                        .sectionTitle(currentSectionTitle)
                        .startLine(blockStart)
                        .endLine(i - 1)
                        .build());
            }
        }

        return blocks;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Checks if a single line is a financial table row.
     * Returns true if the line contains 2+ financial numbers.
     */
    private boolean isTableRow(String line) {
        if (line == null || line.trim().isEmpty()) return false;
        Matcher m = FINANCIAL_NUMBER.matcher(line);
        int count = 0;
        while (m.find()) {
            // Ensure the match is at least 3 chars to avoid false positives on short IDs
            if (m.group().length() >= 3) count++;
        }
        return count >= 2;
    }

    /**
     * Checks if a line is a section header (ALL CAPS, no numbers, short).
     */
    private boolean isSectionHeader(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 4 || trimmed.length() > 120) return false;
        // Must not contain financial numbers
        if (FINANCIAL_NUMBER.matcher(trimmed).find()) return false;
        // Must match ALL CAPS pattern
        return SECTION_HEADER.matcher(trimmed).matches();
    }

    /**
     * Finds where a table block ends, starting from lineIndex.
     * A table ends when we see 2+ consecutive non-table lines.
     */
    private int findTableEnd(String[] lines, int startIndex) {
        int lastTableRow = startIndex - 1;
        int consecutiveNonTable = 0;

        for (int i = startIndex; i < lines.length && i < startIndex + 100; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            if (isTableRow(line) || isColumnHeaderLine(line)) {
                lastTableRow = i;
                consecutiveNonTable = 0;
            } else {
                consecutiveNonTable++;
                if (consecutiveNonTable >= 2) break;
            }
        }

        return lastTableRow;
    }

    /**
     * Checks if a line is a column header line (contains fiscal years like 2022, 2023, 2024
     * or labels like "Q1", "Q2", "First Quarter").
     */
    private boolean isColumnHeaderLine(String line) {
        // Has 2+ fiscal years on one line = year header row
        Matcher yearMatcher = FISCAL_YEAR.matcher(line);
        int yearCount = 0;
        while (yearMatcher.find()) yearCount++;
        return yearCount >= 2;
    }

    /**
     * Extracts column headers (year labels) from a table block.
     * Scans the first few lines of the table for year patterns.
     *
     * Example input line:  "                    2024       2023       2022"
     * Example output:      ["2024", "2023", "2022"]
     */
    private String[] extractColumnHeaders(String[] lines, int tableStart) {
        // Look in the 3 lines around the table start for year headers
        for (int i = Math.max(0, tableStart - 1); i < Math.min(lines.length, tableStart + 3); i++) {
            String line = lines[i].trim();
            List<String> years = new ArrayList<>();
            Matcher m = FISCAL_YEAR.matcher(line);
            while (m.find()) {
                years.add(m.group());
            }
            if (years.size() >= 2) {
                return years.toArray(new String[0]);
            }
        }

        // Fallback: look for period labels like "Q1", "Q2", "Six Months"
        for (int i = Math.max(0, tableStart - 1); i < Math.min(lines.length, tableStart + 2); i++) {
            String line = lines[i].trim();
            if (line.matches(".*Q[1-4].*Q[1-4].*") || line.contains("Quarter")) {
                // Split by 2+ spaces to get column tokens
                String[] tokens = line.split("\\s{2,}");
                List<String> headers = new ArrayList<>();
                for (String t : tokens) {
                    if (!t.trim().isEmpty()) headers.add(t.trim());
                }
                if (headers.size() >= 2) return headers.toArray(new String[0]);
            }
        }

        return new String[]{"Period 1", "Period 2", "Period 3"};
    }
}

