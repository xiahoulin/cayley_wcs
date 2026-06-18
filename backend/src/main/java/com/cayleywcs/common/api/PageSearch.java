package com.cayleywcs.common.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PageSearch {
    private int pageIndex = 1;
    private int pageSize = 20;
    private String sqlTitle = "";
    private List<Map<String, Object>> searchObjects = new ArrayList<>();

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public String getSqlTitle() {
        return sqlTitle;
    }

    public void setSqlTitle(String sqlTitle) {
        this.sqlTitle = sqlTitle;
    }

    public List<Map<String, Object>> getSearchObjects() {
        return searchObjects;
    }

    public void setSearchObjects(List<Map<String, Object>> searchObjects) {
        this.searchObjects = searchObjects;
    }
}
