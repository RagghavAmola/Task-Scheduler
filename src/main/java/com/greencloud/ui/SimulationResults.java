package com.greencloud.ui;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable aggregate representation of simulation results parsed from disk.
 */
public record SimulationResults(
        Path directory,
        List<EventRecord> events,
        Map<String, String> summaryMetrics
) {
    public SimulationResults {
        events = (events != null) ? List.copyOf(events) : List.of();
        summaryMetrics = (summaryMetrics != null) ? Map.copyOf(summaryMetrics) : Map.of();
    }

    public String getMetric(String key) {
        if (summaryMetrics == null || key == null) return "N/A";
        return summaryMetrics.getOrDefault(key.toLowerCase().trim(), "N/A");
    }

    public List<EventRecord> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
