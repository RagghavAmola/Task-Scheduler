package com.greencloud.ui;

/**
 * Immutable record representing an individual scheduler lifecycle event loaded from results/events.csv.
 * Provides explicit JavaBean getters for JavaFX PropertyValueFactory compatibility.
 */
public record EventRecord(
        long timestamp,
        String eventType,
        String taskId,
        String priority,
        double carbonIntensityAtEvent,
        double carbonThreshold
) {
    public long getTimestamp() { return timestamp; }
    public String getEventType() { return eventType; }
    public String getTaskId() { return taskId; }
    public String getPriority() { return priority; }
    public double getCarbonIntensityAtEvent() { return carbonIntensityAtEvent; }
    public double getCarbonThreshold() { return carbonThreshold; }
}
