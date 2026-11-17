package com.greencloud.metrics;

import com.greencloud.model.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MetricsCollectorTest {

    private MetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new MetricsCollector();
    }

    @Test
    @DisplayName("Verify zero initial state metrics and edge cases (no division by zero)")
    void testInitialStateAndZeroDivisionGuards() {
        assertEquals(0, collector.getTotalSubmitted());
        assertEquals(0.0, collector.getCarbonAwareExecutionRate(), 0.001);
        assertEquals(0.0, collector.getPreemptionSuccessRate(), 0.001);
        assertEquals(0.0, collector.getAverageBackgroundLatencyMs(), 0.001);
        assertEquals(0.0, collector.getAverageHighLatencyMs(), 0.001);
        assertEquals(0.0, collector.getAverageCriticalLatencyMs(), 0.001);
    }

    @Test
    @DisplayName("Verify carbon-aware execution rate calculation (excluding CRITICAL tasks)")
    void testCarbonAwareExecutionRateCalculation() {
        // Submit tasks
        collector.taskSubmitted("bg-1", TaskPriority.BACKGROUND, 1000L);
        collector.taskSubmitted("bg-2", TaskPriority.BACKGROUND, 1000L);
        collector.taskSubmitted("high-1", TaskPriority.HIGH, 1000L);
        collector.taskSubmitted("crit-1", TaskPriority.CRITICAL, 1000L);

        // Execute: 2 non-critical under threshold (compliant), 1 non-critical over threshold (bypass), 1 critical
        collector.taskExecuted("bg-1", TaskPriority.BACKGROUND, true, 1100L);   // Compliant
        collector.taskExecuted("bg-2", TaskPriority.BACKGROUND, true, 1200L);   // Compliant
        collector.taskExecuted("high-1", TaskPriority.HIGH, false, 1300L);       // Non-compliant
        collector.taskExecuted("crit-1", TaskPriority.CRITICAL, false, 1050L); // Critical (excluded from rate)

        // Total non-critical completed = 3. Compliant non-critical = 2. Rate = 2/3 * 100 = 66.67%
        assertEquals(3, collector.getNonCriticalCompleted());
        assertEquals(2, collector.getNonCriticalCarbonSatisfied());
        assertEquals(66.666, collector.getCarbonAwareExecutionRate(), 0.01);
    }

    @Test
    @DisplayName("Verify preemption success rate calculation")
    void testPreemptionSuccessRateCalculation() {
        // 3 successful preemptions, 1 waited
        collector.taskPreempted("task-a", "crit-1");
        collector.taskPreempted("task-b", "crit-2");
        collector.taskPreempted("task-c", "crit-3");
        collector.criticalTaskWaited("crit-4");

        // Success rate = 3 / (3 + 1) * 100 = 75.0%
        assertEquals(3, collector.getCriticalTasksThatPreempted());
        assertEquals(1, collector.getCriticalTasksThatWaited());
        assertEquals(75.0, collector.getPreemptionSuccessRate(), 0.01);
    }

    @Test
    @DisplayName("Verify priority-based latency (time-to-execution) averages")
    void testLatencyAverages() {
        long subTime = 1000L;
        collector.taskSubmitted("bg-1", TaskPriority.BACKGROUND, subTime);
        collector.taskSubmitted("high-1", TaskPriority.HIGH, subTime);
        collector.taskSubmitted("crit-1", TaskPriority.CRITICAL, subTime);

        // Execution start times: bg-1 at 1200 (+200ms), high-1 at 1400 (+400ms), crit-1 at 1050 (+50ms)
        collector.taskExecuted("bg-1", TaskPriority.BACKGROUND, true, 1200L);
        collector.taskExecuted("high-1", TaskPriority.HIGH, true, 1400L);
        collector.taskExecuted("crit-1", TaskPriority.CRITICAL, false, 1050L);

        assertEquals(200.0, collector.getAverageBackgroundLatencyMs(), 0.01);
        assertEquals(400.0, collector.getAverageHighLatencyMs(), 0.01);
        assertEquals(50.0, collector.getAverageCriticalLatencyMs(), 0.01);
    }

    @Test
    @DisplayName("Verify CSV export functionality writes expected file content")
    void testCsvExport(@TempDir Path tempDir) throws IOException {
        Path csvPath = tempDir.resolve("metrics").resolve("simulation-metrics.csv");

        collector.taskSubmitted("task-1", TaskPriority.HIGH, 1000L);
        collector.taskExecuted("task-1", TaskPriority.HIGH, true, 1150L);
        collector.taskFailed("task-2", "Timeout error");
        collector.apiFailureHandled("Fallback triggered");

        collector.exportToCsv(csvPath.toString());

        assertTrue(Files.exists(csvPath), "CSV export file should be created");
        String content = Files.readString(csvPath);

        assertTrue(content.contains("Metric,Value"), "CSV must contain header");
        assertTrue(content.contains("Total Tasks Submitted,1"), "CSV must contain total submitted metric");
        assertTrue(content.contains("Tasks Failed,1"), "CSV must contain tasks failed metric");
        assertTrue(content.contains("API Failures Handled,1"), "CSV must contain API failures metric");
    }
}