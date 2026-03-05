package com.genai.bi.model;

public class DocumentChunk {

    private int chunkIndex;
    private ChunkType chunkType;
    private String content;       // NL version — embedded + matched during retrieval
    private String rawContent;    // original text — displayed in UI citations
    private String sectionTitle;  // e.g. "CONSOLIDATED STATEMENTS OF INCOME"
    private float[] embedding;    // 1536-dimensional vector, set by EmbeddingService

    // Default constructor
    public DocumentChunk() {}

    // All-args constructor
    public DocumentChunk(int chunkIndex, ChunkType chunkType, String content,
                         String rawContent, String sectionTitle, float[] embedding) {
        this.chunkIndex   = chunkIndex;
        this.chunkType    = chunkType;
        this.content      = content;
        this.rawContent   = rawContent;
        this.sectionTitle = sectionTitle;
        this.embedding    = embedding;
    }

    // ── Builder ──────────────────────────────────────────────────────────────
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int chunkIndex;
        private ChunkType chunkType;
        private String content;
        private String rawContent;
        private String sectionTitle;
        private float[] embedding;

        public Builder chunkIndex(int chunkIndex)        { this.chunkIndex = chunkIndex; return this; }
        public Builder chunkType(ChunkType chunkType)    { this.chunkType = chunkType; return this; }
        public Builder content(String content)           { this.content = content; return this; }
        public Builder rawContent(String rawContent)     { this.rawContent = rawContent; return this; }
        public Builder sectionTitle(String sectionTitle) { this.sectionTitle = sectionTitle; return this; }
        public Builder embedding(float[] embedding)      { this.embedding = embedding; return this; }

        public DocumentChunk build() {
            return new DocumentChunk(chunkIndex, chunkType, content, rawContent, sectionTitle, embedding);
        }
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────
    public int getChunkIndex()                    { return chunkIndex; }
    public void setChunkIndex(int chunkIndex)     { this.chunkIndex = chunkIndex; }

    public ChunkType getChunkType()               { return chunkType; }
    public void setChunkType(ChunkType chunkType) { this.chunkType = chunkType; }

    public String getContent()                    { return content; }
    public void setContent(String content)        { this.content = content; }

    public String getRawContent()                 { return rawContent; }
    public void setRawContent(String rawContent)  { this.rawContent = rawContent; }

    public String getSectionTitle()               { return sectionTitle; }
    public void setSectionTitle(String t)         { this.sectionTitle = t; }

    public float[] getEmbedding()                 { return embedding; }
    public void setEmbedding(float[] embedding)   { this.embedding = embedding; }
}
