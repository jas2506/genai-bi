package com.genai.bi.service;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * Stage 1: PDF Text Extraction
 *
 * Uses Apache Tika with a generous buffer (-1 = unlimited).
 * For a 10-K annual report (100-200 pages), this extracts the full text
 * preserving rough whitespace layout — critical for table detection downstream.
 *
 * Why BodyContentHandler over Tika.parseToString?
 *   → BodyContentHandler preserves more whitespace alignment between columns,
 *     which our table detector relies on to find financial tables.
 */
@Service
public class TikaExtractorService {

    /**
     * Extracts full text from a PDF (or DOCX) file.
     *
     * @param file  The uploaded MultipartFile
     * @return      Raw extracted text with whitespace preserved
     */
    public String extractText(MultipartFile file) throws Exception {
        // -1 = no character limit (important: 10-K reports are large)
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        AutoDetectParser parser = new AutoDetectParser();

        try (InputStream stream = file.getInputStream()) {
            parser.parse(stream, handler, metadata, context);
        }

        String text = handler.toString();

        if (text == null || text.trim().isEmpty()) {
            throw new RuntimeException(
                    "No text extracted from PDF. " +
                            "If this is a scanned PDF, OCR processing is required (not supported in this version)."
            );
        }

        return text;
    }

    /**
     * Detects the document type from Tika metadata.
     * Useful for routing DOCX vs PDF processing.
     */
    public String detectContentType(MultipartFile file) throws Exception {
        Tika tika = new Tika();
        return tika.detect(file.getInputStream(), file.getOriginalFilename());
    }
}
