package com.greencloud.metrics;

import com.greencloud.model.TaskPriority;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * Uses {@link AtomicInteger}, {@link LongAdder}, and {@link ConcurrentHashMap}
 * for lock-free, thread-safe concurrent operation.
 */
public class MetricsCollector {

    private static final Logger LOGGER = Logger.getLogger(MetricsCollector.class.getName());

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

    // ── Per-task submission timestamps (for latency computation at execution time) ──
    private final ConcurrentHashMap<String, Long> submissionTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TaskPriority> taskPriorities = new ConcurrentHashMap<>();

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
    }

    /**
     * Records a task preemption event (a critical task successfully preempted a running task).
     *
     * @param preemptedTaskId the ID of the task that was preempted
     * @param criticalTaskId  the ID of the critical task that triggered preemption
     */
    public void taskPreempted(String preemptedTaskId, String criticalTaskId) {
        criticalTasksThatPreempted.incrementAndGet();
    }

    /**
     * Records a task resumption event (a previously preempted task resumes execution).
     *
     * @param taskId the ID of the resumed task
     */
    public void taskResumed(String taskId) {
        tasksResumed.incrementAndGet();
    }

    /**
     * Records a task failure event.
     *
     * @param taskId the ID of the failed task
     * @param reason description of the failure
     */
    public void taskFailed(String taskId, String reason) {
        tasksFailed.incrementAndGet();
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
     * Exports all metrics to a CSV file at the specified path.
     * Creates parent directories if they do not exist.
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
