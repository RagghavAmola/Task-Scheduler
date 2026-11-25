package com.greencloud.metrics;

import com.greencloud.model.TaskPriority;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Thread-safe metrics collector that the TaskScheduler reports scheduling events to.
 * <p>
 * Tracks task submissions, executions (carbon-compliant vs CRITICAL bypass),
 * preemptions, resumptions, failures, and API failures. At the end of a simulation run,
 * computes summary statistics including carbon-aware execution rate, preemption success rate,
 * and average time-to-execution by priority level.
 * <p>
 * Exports two persistent CSV files for the post-run dashboard:
 * <ul>
 *   <li>{@code results/events.csv}: Detailed event log (timestamp, eventType, taskId, priority, carbonIntensity, carbonThreshold)</li>
 *   <li>{@code results/summary.csv}: Summary KPIs (carbon-aware rate, preemption rate, avg wait times, task counts)</li>
 * </ul>
 * <p>
 * Uses {@link AtomicInteger}, {@link LongAdder}, and {@link ConcurrentHashMap}
 * for lock-free, thread-safe concurrent operation.
 */
public class MetricsCollector {

    private static final Logger LOGGER = Logger.getLogger(MetricsCollector.class.getName());

    /**
     * Immutable data record representing a single lifecycle event written to {@code events.csv}.
     */
    public record EventEntry(
            long timestamp,
            String eventType,
            String taskId,
            String priority,
            double carbonIntensityAtEvent,
            double carbonThreshold
    ) {}

    // ── Submission counters ──
    private final AtomicInteger totalSubmitted = new AtomicInteger(0);
    private final AtomicInteger criticalSubmitted = new AtomicInteger(0);
    private final AtomicInteger highSubmitted = new AtomicInteger(0);
    private final AtomicInteger backgroundSubmitted = new AtomicInteger(0);

    // ── Execution counters ──
    private final AtomicInteger totalExecuted = new AtomicInteger(0);
    private final AtomicInteger carbonCompliantExecutions = new AtomicInteger(0);
    private final AtomicInteger criticalBypassExecutions = new AtomicInteger(0);

    // ── Non-critical completed tasks that satisfied their carbon threshold at execution time ──
    private final AtomicInteger nonCriticalCompleted = new AtomicInteger(0);
    private final AtomicInteger nonCriticalCarbonSatisfied = new AtomicInteger(0);

    // ── Preemption counters ──
    private final AtomicInteger criticalTasksThatPreempted = new AtomicInteger(0);
    private final AtomicInteger criticalTasksThatWaited = new AtomicInteger(0);

    // ── Resumption, failure, API failure counters ──
    private final AtomicInteger tasksResumed = new AtomicInteger(0);
    private final AtomicInteger tasksFailed = new AtomicInteger(0);
    private final AtomicInteger apiFailuresHandled = new AtomicInteger(0);

    // ── Latency tracking per priority: sum of (executionStartTime - submissionTimestamp) ──
    private final LongAdder backgroundLatencySumMs = new LongAdder();
    private final AtomicInteger backgroundLatencyCount = new AtomicInteger(0);

    private final LongAdder highLatencySumMs = new LongAdder();
    private final AtomicInteger highLatencyCount = new AtomicInteger(0);

    private final LongAdder criticalLatencySumMs = new LongAdder();
    private final AtomicInteger criticalLatencyCount = new AtomicInteger(0);

    // ── Per-task submission timestamps and metadata (for latency & event reporting) ──
    private final ConcurrentHashMap<String, Long> submissionTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TaskPriority> taskPriorities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> taskThresholds = new ConcurrentHashMap<>();

    // ── Sequential event history for events.csv ──
    private final List<EventEntry> eventHistory = new CopyOnWriteArrayList<>();
    private volatile double currentCarbonIntensity = 0.0;

    /**
     * Updates the current environmental grid carbon intensity observed by the collector.
     *
     * @param intensity current carbon intensity in gCO2/kWh
     */
    public void setCurrentCarbonIntensity(double intensity) {
        this.currentCarbonIntensity = intensity;
    }

    public double getCurrentCarbonIntensity() {
        return this.currentCarbonIntensity;
    }

    /**
     * Registers a task's carbon threshold for accurate event logging.
     *
     * @param taskId          task identifier
     * @param carbonThreshold carbon threshold in gCO2/kWh
     */
    public void registerTaskThreshold(String taskId, double carbonThreshold) {
        taskThresholds.put(taskId, carbonThreshold);
    }

    /**
     * Records a task submission event.
     *
     * @param taskId   unique identifier of the submitted task
     * @param priority priority level of the submitted task
     * @param submittedAt timestamp (epoch ms) when the task was submitted
     */
    public void taskSubmitted(String taskId, TaskPriority priority, long submittedAt) {
        totalSubmitted.incrementAndGet();
        submissionTimestamps.put(taskId, submittedAt);
        taskPriorities.put(taskId, priority);

        switch (priority) {
            case CRITICAL -> criticalSubmitted.incrementAndGet();
            case HIGH -> highSubmitted.incrementAndGet();
            case BACKGROUND -> backgroundSubmitted.incrementAndGet();
        }

        double threshold = taskThresholds.getOrDefault(taskId, 0.0);
        recordEvent(submittedAt, "SUBMITTED", taskId, priority != null ? priority.name() : "UNKNOWN", currentCarbonIntensity, threshold);
    }

    /**
     * Records a task submission event with explicit carbon threshold.
     */
    public void taskSubmitted(String taskId, TaskPriority priority, long submittedAt, double carbonThreshold) {
        taskThresholds.put(taskId, carbonThreshold);
        taskSubmitted(taskId, priority, submittedAt);
    }

    /**
     * Records a task execution event.
     *
     * @param taskId           unique identifier of the executed task
     * @param priority         priority level of the executed task
     * @param carbonCompliant  true if the task ran because grid carbon intensity satisfied its threshold;
     *                         false if executed via CRITICAL bypass
     * @param executionStartTime timestamp (epoch ms) when execution started
     */
    public void taskExecuted(String taskId, TaskPriority priority, boolean carbonCompliant, long executionStartTime) {
        totalExecuted.incrementAndGet();

        if (carbonCompliant) {
            carbonCompliantExecutions.incrementAndGet();
        } else {
            criticalBypassExecutions.incrementAndGet();
        }

        // Track non-critical carbon satisfaction rate
        if (priority != TaskPriority.CRITICAL) {
            nonCriticalCompleted.incrementAndGet();
            if (carbonCompliant) {
                nonCriticalCarbonSatisfied.incrementAndGet();
            }
        }

        // Record latency (executionStartTime - submissionTimestamp)
        Long submittedAt = submissionTimestamps.get(taskId);
        if (submittedAt != null) {
            long latencyMs = Math.max(0, executionStartTime - submittedAt);
            switch (priority) {
                case BACKGROUND -> {
                    backgroundLatencySumMs.add(latencyMs);
                    backgroundLatencyCount.incrementAndGet();
                }
                case HIGH -> {
                    highLatencySumMs.add(latencyMs);
                    highLatencyCount.incrementAndGet();
                }
                case CRITICAL -> {
                    criticalLatencySumMs.add(latencyMs);
                    criticalLatencyCount.incrementAndGet();
                }
            }
        }

        double threshold = taskThresholds.getOrDefault(taskId, 0.0);
        recordEvent(executionStartTime, "EXECUTED", taskId, priority != null ? priority.name() : "UNKNOWN", currentCarbonIntensity, threshold);
    }

    /**
     * Records a task preemption event (a critical task successfully preempted a running task).
     *
     * @param preemptedTaskId the ID of the task that was preempted
     * @param criticalTaskId  the ID of the critical task that triggered preemption
     */
    public void taskPreempted(String preemptedTaskId, String criticalTaskId) {
        criticalTasksThatPreempted.incrementAndGet();
        TaskPriority prio = taskPriorities.getOrDefault(preemptedTaskId, TaskPriority.BACKGROUND);
        double threshold = taskThresholds.getOrDefault(preemptedTaskId, 0.0);
        recordEvent(System.currentTimeMillis(), "PREEMPTED", preemptedTaskId, prio.name(), currentCarbonIntensity, threshold);
    }

    /**
     * Records a task resumption event (a previously preempted task resumes execution).
     *
     * @param taskId the ID of the resumed task
     */
    public void taskResumed(String taskId) {
        tasksResumed.incrementAndGet();
        TaskPriority prio = taskPriorities.getOrDefault(taskId, TaskPriority.BACKGROUND);
        double threshold = taskThresholds.getOrDefault(taskId, 0.0);
        recordEvent(System.currentTimeMillis(), "RESUMED", taskId, prio.name(), currentCarbonIntensity, threshold);
    }

    /**
     * Records a task completion event.
     */
    public void taskCompleted(String taskId) {
        TaskPriority prio = taskPriorities.getOrDefault(taskId, TaskPriority.BACKGROUND);
        double threshold = taskThresholds.getOrDefault(taskId, 0.0);
        recordEvent(System.currentTimeMillis(), "COMPLETED", taskId, prio.name(), currentCarbonIntensity, threshold);
    }

    /**
     * Records a task deferral event (candidate task could not execute due to high carbon intensity).
     */
    public void taskDeferred(String taskId, TaskPriority priority, double currentIntensity, double threshold) {
        taskThresholds.put(taskId, threshold);
        recordEvent(System.currentTimeMillis(), "DEFERRED", taskId, priority != null ? priority.name() : "BACKGROUND", currentIntensity, threshold);
    }

    /**
     * Records a task failure event.
     *
     * @param taskId the ID of the failed task
     * @param reason description of the failure
     */
    public void taskFailed(String taskId, String reason) {
        tasksFailed.incrementAndGet();
        TaskPriority prio = taskPriorities.getOrDefault(taskId, TaskPriority.BACKGROUND);
        double threshold = taskThresholds.getOrDefault(taskId, 0.0);
        recordEvent(System.currentTimeMillis(), "FAILED", taskId, prio.name(), currentCarbonIntensity, threshold);
    }

    /**
     * Records that a critical task had to wait in queue instead of immediately preempting.
     *
     * @param criticalTaskId the ID of the critical task that waited
     */
    public void criticalTaskWaited(String criticalTaskId) {
        criticalTasksThatWaited.incrementAndGet();
    }

    /**
     * Records an API failure that was handled (e.g., carbon intensity provider fallback).
     *
     * @param reason description of the API failure
     */
    public void apiFailureHandled(String reason) {
        apiFailuresHandled.incrementAndGet();
    }

    /**
     * Directly appends an event entry to the event history.
     */
    public void recordEvent(long timestamp, String eventType, String taskId, String priority, double carbonIntensityAtEvent, double carbonThreshold) {
        eventHistory.add(new EventEntry(timestamp, eventType, taskId, priority, carbonIntensityAtEvent, carbonThreshold));
    }

    public List<EventEntry> getEventHistory() {
        return Collections.unmodifiableList(eventHistory);
    }

    // ── Computed Metrics ──

    /**
     * Computes the carbon-aware execution rate: percentage of non-critical completed tasks
     * that satisfied their carbon threshold at execution time.
     * <p>
     * CRITICAL tasks are explicitly excluded since they bypass carbon gating.
     *
     * @return carbon-aware execution rate as a percentage [0.0, 100.0], or 0.0 if no non-critical tasks completed
     */
    public double getCarbonAwareExecutionRate() {
        int total = nonCriticalCompleted.get();
        if (total == 0) return 0.0;
        return ((double) nonCriticalCarbonSatisfied.get() / total) * 100.0;
    }

    /**
     * Computes the preemption success rate: percentage of critical tasks that successfully
     * preempted a running task vs. those that had to wait in queue.
     *
     * @return preemption success rate as a percentage [0.0, 100.0], or 0.0 if no critical tasks were submitted
     */
    public double getPreemptionSuccessRate() {
        int preempted = criticalTasksThatPreempted.get();
        int waited = criticalTasksThatWaited.get();
        int total = preempted + waited;
        if (total == 0) return 0.0;
        return ((double) preempted / total) * 100.0;
    }

    /**
     * Computes the average time-to-execution (latency) for BACKGROUND tasks.
     *
     * @return average latency in milliseconds, or 0.0 if no background tasks were executed
     */
    public double getAverageBackgroundLatencyMs() {
        int count = backgroundLatencyCount.get();
        if (count == 0) return 0.0;
        return (double) backgroundLatencySumMs.sum() / count;
    }

    /**
     * Computes the average time-to-execution (latency) for HIGH priority tasks.
     *
     * @return average latency in milliseconds, or 0.0 if no high-priority tasks were executed
     */
    public double getAverageHighLatencyMs() {
        int count = highLatencyCount.get();
        if (count == 0) return 0.0;
        return (double) highLatencySumMs.sum() / count;
    }

    /**
     * Computes the average time-to-execution (latency) for CRITICAL tasks.
     *
     * @return average latency in milliseconds, or 0.0 if no critical tasks were executed
     */
    public double getAverageCriticalLatencyMs() {
        int count = criticalLatencyCount.get();
        if (count == 0) return 0.0;
        return (double) criticalLatencySumMs.sum() / count;
    }

    // ── Raw Counter Accessors ──

    public int getTotalSubmitted() { return totalSubmitted.get(); }
    public int getCriticalSubmitted() { return criticalSubmitted.get(); }
    public int getHighSubmitted() { return highSubmitted.get(); }
    public int getBackgroundSubmitted() { return backgroundSubmitted.get(); }
    public int getTotalExecuted() { return totalExecuted.get(); }
    public int getCarbonCompliantExecutions() { return carbonCompliantExecutions.get(); }
    public int getCriticalBypassExecutions() { return criticalBypassExecutions.get(); }
    public int getNonCriticalCompleted() { return nonCriticalCompleted.get(); }
    public int getNonCriticalCarbonSatisfied() { return nonCriticalCarbonSatisfied.get(); }
    public int getCriticalTasksThatPreempted() { return criticalTasksThatPreempted.get(); }
    public int getCriticalTasksThatWaited() { return criticalTasksThatWaited.get(); }
    public int getTasksResumed() { return tasksResumed.get(); }
    public int getTasksFailed() { return tasksFailed.get(); }
    public int getApiFailuresHandled() { return apiFailuresHandled.get(); }

    /**
     * Prints a formatted summary of all collected metrics to the logger.
     */
    public void printSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════════════╗\n");
        sb.append("║           METRICS COLLECTOR — SIMULATION REPORT             ║\n");
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  Total Tasks Submitted        : %-28d║\n", getTotalSubmitted()));
        sb.append(String.format("║    ├─ CRITICAL                : %-28d║\n", getCriticalSubmitted()));
        sb.append(String.format("║    ├─ HIGH                    : %-28d║\n", getHighSubmitted()));
        sb.append(String.format("║    └─ BACKGROUND              : %-28d║\n", getBackgroundSubmitted()));
        sb.append(String.format("║  Total Tasks Executed         : %-28d║\n", getTotalExecuted()));
        sb.append(String.format("║    ├─ Carbon-Compliant        : %-28d║\n", getCarbonCompliantExecutions()));
        sb.append(String.format("║    └─ Critical Bypass         : %-28d║\n", getCriticalBypassExecutions()));
        sb.append(String.format("║  Tasks Resumed                : %-28d║\n", getTasksResumed()));
        sb.append(String.format("║  Tasks Failed                 : %-28d║\n", getTasksFailed()));
        sb.append(String.format("║  API Failures Handled         : %-28d║\n", getApiFailuresHandled()));
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  Carbon-Aware Execution Rate  : %-27.1f%%║\n", getCarbonAwareExecutionRate()));
        sb.append(String.format("║  Preemption Success Rate      : %-27.1f%%║\n", getPreemptionSuccessRate()));
        sb.append(String.format("║  Avg Latency (BACKGROUND)     : %-24.1f ms ║\n", getAverageBackgroundLatencyMs()));
        sb.append(String.format("║  Avg Latency (HIGH)           : %-24.1f ms ║\n", getAverageHighLatencyMs()));
        sb.append(String.format("║  Avg Latency (CRITICAL)       : %-24.1f ms ║\n", getAverageCriticalLatencyMs()));
        sb.append("╚══════════════════════════════════════════════════════════════╝\n");

        LOGGER.info(sb.toString());
    }

    /**
     * Exports both {@code results/events.csv} and {@code results/summary.csv} to the specified directory.
     *
     * @param resultsDir directory where results CSV files should be written
     * @throws IOException if file creation or writing fails
     */
    public void exportResults(Path resultsDir) throws IOException {
        Files.createDirectories(resultsDir);

        // a) results/events.csv -> timestamp,eventType,taskId,priority,carbonIntensityAtEvent,carbonThreshold
        Path eventsPath = resultsDir.resolve("events.csv");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(eventsPath))) {
            writer.println("timestamp,eventType,taskId,priority,carbonIntensityAtEvent,carbonThreshold");
            for (EventEntry entry : eventHistory) {
                writer.printf("%d,%s,%s,%s,%.2f,%.2f%n",
                        entry.timestamp(),
                        entry.eventType(),
                        entry.taskId(),
                        entry.priority(),
                        entry.carbonIntensityAtEvent(),
                        entry.carbonThreshold());
            }
        }

        // b) results/summary.csv -> metric,value
        Path summaryPath = resultsDir.resolve("summary.csv");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(summaryPath))) {
            writer.println("metric,value");
            writer.printf("carbon-aware execution rate,%.2f%%%n", getCarbonAwareExecutionRate());
            writer.printf("preemption success rate,%.2f%%%n", getPreemptionSuccessRate());
            writer.printf("avg background wait time,%.2f ms%n", getAverageBackgroundLatencyMs());
            writer.printf("avg critical time-to-execution,%.2f ms%n", getAverageCriticalLatencyMs());
            writer.printf("avg high time-to-execution,%.2f ms%n", getAverageHighLatencyMs());
            writer.println("total submitted," + getTotalSubmitted());
            writer.println("total completed," + (getTotalExecuted() - getTasksFailed()));
            writer.println("total preempted," + getCriticalTasksThatPreempted());
            writer.println("total failed," + getTasksFailed());
        }

        LOGGER.info(() -> String.format("[METRICS] Exported results to directory: %s", resultsDir.toAbsolutePath()));
    }

    /**
     * Convenience method to export results to string directory path.
     */
    public void exportResults(String dirPath) throws IOException {
        exportResults(Paths.get(dirPath));
    }

    /**
     * Default export writes to {@code results/} directory.
     */
    public void exportResults() throws IOException {
        exportResults("results");
    }

    /**
     * Exports all metrics to a single CSV file at the specified path (backward compatibility).
     *
     * @param filePath path where the CSV report should be written
     * @throws IOException if file creation or writing fails
     */
    public void exportToCsv(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        Files.createDirectories(path.getParent());

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path))) {
            writer.println("Metric,Value");
            writer.println("Timestamp," + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            writer.println("Total Tasks Submitted," + getTotalSubmitted());
            writer.println("Critical Submitted," + getCriticalSubmitted());
            writer.println("High Submitted," + getHighSubmitted());
            writer.println("Background Submitted," + getBackgroundSubmitted());
            writer.println("Total Tasks Executed," + getTotalExecuted());
            writer.println("Carbon-Compliant Executions," + getCarbonCompliantExecutions());
            writer.println("Critical Bypass Executions," + getCriticalBypassExecutions());
            writer.println("Non-Critical Completed," + getNonCriticalCompleted());
            writer.println("Non-Critical Carbon Satisfied," + getNonCriticalCarbonSatisfied());
            writer.println("Critical Tasks That Preempted," + getCriticalTasksThatPreempted());
            writer.println("Critical Tasks That Waited," + getCriticalTasksThatWaited());
            writer.println("Tasks Resumed," + getTasksResumed());
            writer.println("Tasks Failed," + getTasksFailed());
            writer.println("API Failures Handled," + getApiFailuresHandled());
            writer.printf("Carbon-Aware Execution Rate (%%),%.2f%n", getCarbonAwareExecutionRate());
            writer.printf("Preemption Success Rate (%%),%.2f%n", getPreemptionSuccessRate());
            writer.printf("Avg Latency BACKGROUND (ms),%.2f%n", getAverageBackgroundLatencyMs());
            writer.printf("Avg Latency HIGH (ms),%.2f%n", getAverageHighLatencyMs());
            writer.printf("Avg Latency CRITICAL (ms),%.2f%n", getAverageCriticalLatencyMs());
        }

        LOGGER.info(() -> String.format("[METRICS] CSV report exported to: %s", path.toAbsolutePath()));
    }

    /**
     * Convenience method: exports to the default CI artifact path.
     *
     * @throws IOException if file creation or writing fails
     */
    public void exportToDefaultCsv() throws IOException {
        exportToCsv("target/metrics/simulation-metrics.csv");
    }

    @Override
    public String toString() {
        return String.format("MetricsCollector[submitted=%d, executed=%d, carbonRate=%.1f%%, preemptRate=%.1f%%, failed=%d]",
                getTotalSubmitted(), getTotalExecuted(), getCarbonAwareExecutionRate(),
                getPreemptionSuccessRate(), getTasksFailed());
    }
}
