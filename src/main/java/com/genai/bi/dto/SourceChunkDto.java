package com.genai.bi.dto;

/**
 * Represents a single retrieved chunk shown as a citation in the UI.
 * Nested inside RAGQueryResponse.sources list.
 *
 * Example JSON element:
 * {
 *   "content": "Net revenues   52,931   49,282   43,151",
 *   "sectionTitle": "CONSOLIDATED STATEMENTS OF INCOME",
 *   "chunkType": "table",
 *   "similarity": 0.94
 * }
 */
public class SourceChunkDto {

    private String content;       // text shown as citation in the UI
    private String sectionTitle;  // which section of the 10-K this came from
    private String chunkType;     // "table" or "narrative"
    private double similarity;    // cosine similarity score 0.0 to 1.0

    public SourceChunkDto() {}

    // ── Builder ───────────────────────────────────────────────────────────────
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String content;
        private String sectionTitle;
        private String chunkType;
        private double similarity;

        public Builder content(String content)           { this.content = content; return this; }
        public Builder sectionTitle(String sectionTitle) { this.sectionTitle = sectionTitle; return this; }
        public Builder chunkType(String chunkType)       { this.chunkType = chunkType; return this; }
        public Builder similarity(double similarity)     { this.similarity = similarity; return this; }

        public SourceChunkDto build() {
            SourceChunkDto dto = new SourceChunkDto();
            dto.content      = this.content;
            dto.sectionTitle = this.sectionTitle;
            dto.chunkType    = this.chunkType;
            dto.similarity   = this.similarity;
            return dto;
        }
    }

    // ── Getters & Setters ──────────────────────────────────────────────────────
    public String getContent()                    { return content; }
    public void   setContent(String content)      { this.content = content; }

    public String getSectionTitle()               { return sectionTitle; }
    public void   setSectionTitle(String t)       { this.sectionTitle = t; }

    public String getChunkType()                  { return chunkType; }
    public void   setChunkType(String chunkType)  { this.chunkType = chunkType; }

    public double getSimilarity()                 { return similarity; }
    public void   setSimilarity(double similarity){ this.similarity = similarity; }
}

