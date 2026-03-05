package com.genai.bi.model;

public class TextBlock {

    private ChunkType type;
    private String text;
    private String sectionTitle;
    private String[] columnHeaders;
    private int startLine;
    private int endLine;

    // Default constructor (required by some frameworks)
    public TextBlock() {}

    // All-args constructor
    public TextBlock(ChunkType type, String text, String sectionTitle,
                     String[] columnHeaders, int startLine, int endLine) {
        this.type          = type;
        this.text          = text;
        this.sectionTitle  = sectionTitle;
        this.columnHeaders = columnHeaders;
        this.startLine     = startLine;
        this.endLine       = endLine;
    }

    // ── Builder ──────────────────────────────────────────────────────────────
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private ChunkType type;
        private String text;
        private String sectionTitle;
        private String[] columnHeaders;
        private int startLine;
        private int endLine;

        public Builder type(ChunkType type)               { this.type = type; return this; }
        public Builder text(String text)                  { this.text = text; return this; }
        public Builder sectionTitle(String sectionTitle)  { this.sectionTitle = sectionTitle; return this; }
        public Builder columnHeaders(String[] headers)    { this.columnHeaders = headers; return this; }
        public Builder startLine(int startLine)           { this.startLine = startLine; return this; }
        public Builder endLine(int endLine)               { this.endLine = endLine; return this; }

        public TextBlock build() {
            return new TextBlock(type, text, sectionTitle, columnHeaders, startLine, endLine);
        }
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────
    public ChunkType getType()                 { return type; }
    public void setType(ChunkType type)        { this.type = type; }

    public String getText()                    { return text; }
    public void setText(String text)           { this.text = text; }

    public String getSectionTitle()            { return sectionTitle; }
    public void setSectionTitle(String t)      { this.sectionTitle = t; }

    public String[] getColumnHeaders()         { return columnHeaders; }
    public void setColumnHeaders(String[] h)   { this.columnHeaders = h; }

    public int getStartLine()                  { return startLine; }
    public void setStartLine(int startLine)    { this.startLine = startLine; }

    public int getEndLine()                    { return endLine; }
    public void setEndLine(int endLine)        { this.endLine = endLine; }
}
