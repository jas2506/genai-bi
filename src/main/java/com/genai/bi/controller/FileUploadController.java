package com.genai.bi.controller;

import com.genai.bi.service.CsvService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
@CrossOrigin(origins = "*")
public class FileUploadController {

    private final CsvService csvService;

    public FileUploadController(CsvService csvService) {
        this.csvService = csvService;
    }

    @PostMapping(value = "/upload-csv", consumes = "multipart/form-data")
    public ResponseEntity<String> uploadCsv(@RequestParam("file") MultipartFile file) {
        csvService.processCsv(file);
        return ResponseEntity.ok("CSV processed successfully");
    }
}