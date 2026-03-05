package com.genai.bi.dto;

import java.util.List;
import java.util.Map;

public class QueryResponse {

    private String queryType;
    private String chartType;
    private String xAxis;
    private String yAxis;
    private String insight;
    private List<Map<String,Object>> data;


    public String getChartType() {
        return chartType;
    }

    public void setChartType(String chartType) {
        this.chartType = chartType;
    }

    public String getXAxis() {
        return xAxis;
    }

    public void setXAxis(String xAxis) {
        this.xAxis = xAxis;
    }

    public String getYAxis() {
        return yAxis;
    }

    public void setYAxis(String yAxis) {
        this.yAxis = yAxis;
    }

    public String getInsight() {
        return insight;
    }

    public void setInsight(String insight) {
        this.insight = insight;
    }

    public List<Map<String,Object>> getData() {
        return data;
    }

    public void setData(List<Map<String,Object>> data) {
        this.data = data;
    }

    public String getQueryType() {
        return queryType;
    }

    public void setQueryType(String queryType) {
        this.queryType = queryType;
    }
}