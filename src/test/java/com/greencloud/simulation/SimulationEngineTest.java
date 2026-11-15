package com.greencloud.simulation;

import com.greencloud.env.CarbonIntensitySnapshot;
import com.greencloud.model.BackupTask;
import com.greencloud.model.TaskPriority;
import com.greencloud.model.TaskStatus;
import com.greencloud.view.ConsoleView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SimulationEngineTest {

    @Test
    @DisplayName("Verify SimulationEngine runs end-to-end with preemption, green-gating, and complete lifecycle")
    void testSimulationRunsToEndToEndCompletion() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(buffer);
        ConsoleView view = new ConsoleView(printStream, System.currentTimeMillis());
        view.setAnsiEnabled(false); // test with plain text for deterministic assertions

        // Run a fast 10-second simulation with 400ms tick interval
        SimulationEngine engine = new SimulationEngine(10, 400, view);
        SimulationStats stats = engine.run();

        assertNotNull(stats);
        assertEquals(6, stats.getTasksSubmitted(), "Should have submitted all 6 configured workloads");
        assertEquals(6, stats.getTasksCompleted(), "All 6 workloads should have reached COMPLETED status");
        assertTrue(stats.getTasksPreempted() >= 1, "At least 1 preemption should have occurred when CRITICAL task arrived");
        assertTrue(stats.getBackgroundTasksGreenGated() >= 3, "Background tasks should have executed when carbon threshold satisfied");
        assertEquals(0, stats.getTasksFailed(), "No tasks should have failed");
        assertTrue(stats.isAllSuccess());
        assertTrue(stats.getTicksExecuted() > 0);

        String consoleOutput = buffer.toString();
        assertTrue(consoleOutput.contains("GREEN CLOUD CARBON-AWARE TASK SCHEDULER"), "Should print banner");
        assertTrue(consoleOutput.contains("SIMULATION SUMMARY"), "Should print summary block");
        assertTrue(consoleOutput.contains("Total Tasks Submitted"), "Should include submitted count in summary");
        assertTrue(consoleOutput.contains("Total Tasks Preempted"), "Should include preemption count in summary");
    }

    @Test
    @DisplayName("Verify ConsoleView ANSI color formatting and plain text graceful degradation")
    void testConsoleViewAnsiAndPlainTextFallback() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(buffer);
        ConsoleView view = new ConsoleView(printStream, System.currentTimeMillis());

        // Test with ANSI enabled
        view.setAnsiEnabled(true);
        assertTrue(view.isAnsiEnabled());
        String coloredGreen = view.colorize("SUCCESS", ConsoleView.ANSI_GREEN);
        assertTrue(coloredGreen.contains("\u001B[32m"), "Should contain green escape sequence");
        assertTrue(coloredGreen.contains("\u001B[0m"), "Should contain reset escape sequence");

        // Test with ANSI disabled (graceful fallback)
        view.setAnsiEnabled(false);
        assertFalse(view.isAnsiEnabled());
        String plain = view.colorize("SUCCESS", ConsoleView.ANSI_GREEN);
        assertEquals("SUCCESS", plain, "Should strip ANSI escape sequences in fallback mode");

        // Verify timestamp prefix format [MM:SS]
        String prefix = view.getTimestampPrefix();
        assertTrue(prefix.matches("\\[\\d{2}:\\d{2}\\] "), "Timestamp prefix must match [MM:SS] format, got: " + prefix);

        // Test event outputs in plain text
        CarbonIntensitySnapshot snapshot = new CarbonIntensitySnapshot("TEST-UK", 145.0, Instant.now());
        view.printTickHeader(1, snapshot);
        view.printCarbonIntensityUpdate(snapshot);

        BackupTask task = new BackupTask("test-bg-task", TaskPriority.BACKGROUND, 100, 150.0);
        view.printTaskSubmitted(task, 1);
        view.printTaskEligible(task, 145.0);
        view.printTaskDeferred(task, 160.0);
        view.printTaskCompleted(task);

        BackupTask crit = new BackupTask("test-crit-task", TaskPriority.CRITICAL, 50, 50.0);
        view.printPreemptionTriggered(crit, task);
        view.printTaskPreempted(task, crit);
        view.printCriticalBypass(crit);
        view.printTaskResumed(task);

        SimulationStats stats = new SimulationStats();
        stats.recordSubmission();
        stats.recordCompletion();
        stats.recordPreemption();
        stats.recordGreenGatedBackgroundTask();
        stats.finish();
        view.printSummary(stats);

        String output = buffer.toString();
        assertFalse(output.contains("\u001B["), "Plain text fallback output must not contain any raw escape sequences");
        assertTrue(output.contains("TICK #01"));
        assertTrue(output.contains("Carbon Intensity"));
        assertTrue(output.contains("ELIGIBLE"));
        assertTrue(output.contains("DEFERRED"));
        assertTrue(output.contains("PREEMPTED"));
        assertTrue(output.contains("CRITICAL BYPASS"));
        assertTrue(output.contains("RESUMED"));
        assertTrue(output.contains("SIMULATION SUMMARY"));
    }

    @Test
    @DisplayName("Verify SimulationEngine parameters and configurable duration")
    void testSimulationConfigurableDurationAndParameters() {
        SimulationEngine customEngine = new SimulationEngine(45, 1500);
        assertEquals(45, customEngine.getDurationSeconds(), "Duration should be configurable");
        assertEquals(1500, customEngine.getTickIntervalMillis(), "Tick interval should be configurable");
        assertNotNull(customEngine.getProvider());
        assertNotNull(customEngine.getConsoleView());

        SimulationEngine defaultEngine = new SimulationEngine();
        assertEquals(SimulationEngine.DEFAULT_DURATION_SECONDS, defaultEngine.getDurationSeconds());
        assertEquals(SimulationEngine.DEFAULT_TICK_INTERVAL_MILLIS, defaultEngine.getTickIntervalMillis());
    }
}
