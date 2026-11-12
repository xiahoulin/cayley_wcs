package com.cayleywcs.dashboard;

import java.util.List;
import java.util.Map;

public interface DashboardService {
    List<Map<String,Object>> devices();
    void saveSelection(Selection selection);
    record Selection(Long appId, Long revisionId, List<String> fields) { }
}
