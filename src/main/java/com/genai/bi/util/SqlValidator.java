package com.genai.bi.util;

public class SqlValidator {

    public static boolean isSafe(String sql){

        if(sql == null) return false;

        String lower = sql.toLowerCase().trim();

        if(!lower.startsWith("select"))
            return false;

        if(lower.contains("drop") ||
                lower.contains("delete") ||
                lower.contains("update") ||
                lower.contains("insert") ||
                lower.contains("alter"))
            return false;

        return true;
    }
}