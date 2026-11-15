package com.greencloud.view;

import com.greencloud.env.CarbonIntensitySnapshot;
import com.greencloud.model.ComputeTask;
import com.greencloud.simulation.SimulationStats;

import java.io.PrintStream;

/**
 * ConsoleView provides real-time, ANSI-colorized, and structured timeline formatting
 * for the Green Cloud Carbon-Aware Task Scheduler simulation.
 * <p>
 * Decoupled from core scheduling algorithms to ensure separation of concerns.
 * Includes automatic ANSI detection with graceful degradation to plain uncolored text.
 */
public class ConsoleView {

    // ANSI Color Escape Sequences
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_BOLD = "\u001B[1m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_MAGENTA = "\u001B[35m";
    public static final String ANSI_CYAN = "\u001B[36m";
    public static final String ANSI_WHITE = "\u001B[37m";
    public static final String ANSI_GRAY = "\u001B[90m";

    // Bold Variants
    public static final String ANSI_BOLD_RED = "\u001B[1;31m";
    public static final String ANSI_BOLD_GREEN = "\u001B[1;32m";
    public static final String ANSI_BOLD_YELLOW = "\u001B[1;33m";
    public static final String ANSI_BOLD_MAGENTA = "\u001B[1;35m";
    public static final String ANSI_BOLD_CYAN = "\u001B[1;36m";
    public static final String ANSI_BOLD_WHITE = "\u001B[1;37m";

    private final PrintStream out;
    private volatile long startTimeMillis;
    private volatile boolean ansiEnabled;

    public ConsoleView() {
        this(System.out, System.currentTimeMillis());
    }

    public ConsoleView(PrintStream out) {
        this(out, System.currentTimeMillis());
    }

    public ConsoleView(PrintStream out, long startTimeMillis) {
        this.out = (out != null) ? out : System.out;
        this.startTimeMillis = startTimeMillis;
        this.ansiEnabled = detectAnsiSupport();
    }

    /**
     * Detects if the current environment supports ANSI escape sequences.
     * Checks explicit system properties, NO_COLOR standard, and known terminal variables.
     *
     * @return true if ANSI colors should be enabled, false otherwise
     */
    public static boolean detectAnsiSupport() {
        // 1. Explicit property override: -Dgreencloud.ansi=true/false
        String explicitProp = System.getProperty("greencloud.ansi");
        if (explicitProp != null) {
            return Boolean.parseBoolean(explicitProp);
        }

        // 2. Standard NO_COLOR specification (https://no-color.org)
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }

        // 3. Known modern terminals supporting ANSI colors
        if (System.getenv("WT_SESSION") != null ||             // Windows Terminal
                System.getenv("VSCODE_PID") != null ||         // VS Code integrated terminal
                System.getenv("ConEmuPID") != null ||          // ConEmu / Cmder
                System.getenv("ANSICON") != null) {            // ANSICON
            return true;
        }

        String term = System.getenv("TERM");
        if (term != null && (term.contains("color") || term.contains("xterm") || term.contains("vt100") || term.contains("ansi"))) {
            return true;
        }

        // 4. Non-Windows OS with Console or interactive TTY
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return System.console() != null;
        }

        // 5. Windows 10 build 10586+ / Windows 11 console host supports ANSI by default
        return System.console() != null;
    }

    /**
     * Colorizes text if ANSI mode is enabled; otherwise returns text uncolored.
     */
    public String colorize(String text, String ansiCode) {
        if (!ansiEnabled || ansiCode == null || ansiCode.isEmpty()) {
            return text;
        }
        return ansiCode + text + ANSI_RESET;
    }

    /**
     * Formats the elapsed time since simulation start as [MM:SS].
     */
    public String getTimestampPrefix() {
        long elapsed = Math.max(0, System.currentTimeMillis() - startTimeMillis);
        long totalSecs = elapsed / 1000;
        long minutes = totalSecs / 60;
        long seconds = totalSecs % 60;
        return String.format("[%02d:%02d] ", minutes, seconds);
    }

    public void printBanner(int durationSeconds, int tickIntervalMillis, String providerMode) {
        out.println();
        out.println(colorize("==========================================================================================", ANSI_BOLD_CYAN));
        out.println(colorize("||                      GREEN CLOUD CARBON-AWARE TASK SCHEDULER                         ||", ANSI_BOLD_WHITE));
        out.println(colorize("||                           End-to-End Simulation Engine                               ||", ANSI_BOLD_WHITE));
        out.println(colorize("==========================================================================================", ANSI_BOLD_CYAN));
        out.println(String.format("   Configured Duration : %d seconds  |  Tick Schedule: every %d ms  |  Provider: %s",
                durationSeconds, tickIntervalMillis, providerMode));
        out.println(String.format("   ANSI Colors         : %s        |  Start Time   : %s",
                ansiEnabled ? colorize("ENABLED", ANSI_BOLD_GREEN) : "DISABLED", getTimestampPrefix().trim()));
        out.println(colorize("------------------------------------------------------------------------------------------", ANSI_GRAY));
        out.println();
    }

    public void printTickHeader(int tickNumber, CarbonIntensitySnapshot snapshot) {
        String level = getIntensityLevel(snapshot.carbonIntensityGCO2kWh());
        String intensityStr = String.format("%.1f gCO2/kWh [%s, %s]",
                snapshot.carbonIntensityGCO2kWh(), level, snapshot.region());

        out.println(colorize(String.format("+--- [TICK #%02d] -----------------------------------------------------------------------+", tickNumber), ANSI_CYAN));
        out.println(getTimestampPrefix() + colorize("Carbon Intensity Signal -> " + intensityStr, ANSI_CYAN));
    }

    public void printTickFooter(int tickNumber) {
        out.println(colorize(String.format("+----------------------------------------------------------------------- [END TICK #%02d] -+", tickNumber), ANSI_CYAN));
        out.println();
    }

    public void printCarbonIntensityUpdate(CarbonIntensitySnapshot snapshot) {
        String level = getIntensityLevel(snapshot.carbonIntensityGCO2kWh());
        out.println(getTimestampPrefix() + colorize(
                String.format("Carbon intensity update: %.1f gCO2/kWh (%s) [%s]",
                        snapshot.carbonIntensityGCO2kWh(), snapshot.region(), level), ANSI_CYAN));
    }

public void printTaskSubmitted(ComputeTask task, int totalPending) {
        if (task == null) {
            out.println(getTimestampPrefix() + colorize("[SUBMIT] Task [null] added. Pending: " + totalPending, ANSI_WHITE));
            return;
        }
        out.println(getTimestampPrefix() + colorize(
                String.format("[SUBMIT] Task [%s] added (Priority: %s, Threshold: %.1f gCO2/kWh, Duration: %d ms). Pending: %d",
                        task.getTaskId(), task.getPriority(), task.getCarbonThresholdGramsPerKwh(),
                        task.getDurationMillis(), totalPending), ANSI_WHITE));
    }

    public void printTaskEligible(ComputeTask task, double currentCarbonIntensity) {
        if (task == null) return;
        out.println(getTimestampPrefix() + colorize(
                String.format("  -> [ELIGIBLE] Task [%s] (Priority: %s, Threshold: %.1f gCO2/kWh) <= Current (%.1f gCO2/kWh). Starting execution...",
                        task.getTaskId(), task.getPriority(), task.getCarbonThresholdGramsPerKwh(), currentCarbonIntensity), ANSI_BOLD_GREEN));
    }

    public void printTaskDeferred(ComputeTask task, double currentCarbonIntensity) {
        if (task == null) return;
        out.println(getTimestampPrefix() + colorize(
                String.format("  -> [DEFERRED] Task [%s] (Priority: %s, Threshold: %.1f gCO2/kWh) < Current (%.1f gCO2/kWh). Waiting for greener energy.",
                        task.getTaskId(), task.getPriority(), task.getCarbonThresholdGramsPerKwh(), currentCarbonIntensity), ANSI_BOLD_YELLOW));
    }

    public void printTaskCompleted(ComputeTask task) {
        if (task == null) return;
        out.println(getTimestampPrefix() + colorize(
                String.format("  -> [COMPLETED] Task [%s] finished successfully (Remaining work: 0 ms).",
                        task.getTaskId()), ANSI_GREEN));
    }

    public void printTaskFailed(ComputeTask task, String reason) {
        if (task == null) return;
        out.println(getTimestampPrefix() + colorize(
                String.format("  -> [FAILED] Task [%s] encountered an error: %s",
                        task.getTaskId(), reason != null ? reason : "Unknown error"), ANSI_BOLD_RED));
    }

    public void printPreemptionTriggered(ComputeTask criticalTask, ComputeTask runningTask) {
        String critId = (criticalTask != null) ? criticalTask.getTaskId() : "null";
        String runId = (runningTask != null) ? runningTask.getTaskId() : "null";
        out.println(getTimestampPrefix() + colorize(
                String.format("  !! [PREEMPTION TRIGGER] Critical Task [%s] submitted while Task [%s] is RUNNING. Sending interrupt signal!",
                        critId, runId), ANSI_BOLD_RED));
    }

    public void printTaskPreempted(ComputeTask runningTask, ComputeTask criticalTask) {
        if (runningTask == null) return;
        out.println(getTimestampPrefix() + colorize(
                String.format("  !! [PREEMPTED] Task [%s] paused at %.1f%% completion (remaining: %d ms). Context pushed onto preemption stack.",
                        runningTask.getTaskId(), runningTask.getCompletionPercentage(), runningTask.getRemainingDurationMillis()), ANSI_BOLD_RED));
    }

    public void printCriticalBypass(ComputeTask criticalTask) {
        if (criticalTask == null) return;
        out.println(getTimestampPrefix() + colorize(
                String.format("  >> [CRITICAL BYPASS] Task [%s] (CRITICAL) instantly bypasses carbon gating. Executing immediately!",
                        criticalTask.getTaskId()), ANSI_BOLD_MAGENTA));
    }

    public void printTaskResumed(ComputeTask resumedTask) {
        if (resumedTask == null) return;
        out.println(getTimestampPrefix() + colorize(
                String.format("  << [RESUMED] Resumed preempted Task [%s] from stack (remaining duration: %d ms). Resuming execution...",
                        resumedTask.getTaskId(), resumedTask.getRemainingDurationMillis()), ANSI_GREEN));
    }

    public void printSummary(SimulationStats stats) {
        out.println();
        out.println(colorize("==========================================================================================", ANSI_BOLD_CYAN));
        out.println(colorize("||                                  SIMULATION SUMMARY                                  ||", ANSI_BOLD_WHITE));
        out.println(colorize("==========================================================================================", ANSI_BOLD_CYAN));
        out.println(String.format("   Total Simulation Runtime    : %.2f seconds", stats.getTotalDurationSeconds()));
        out.println(String.format("   Total Scheduling Ticks      : %d", stats.getTicksExecuted()));
        out.println(colorize("------------------------------------------------------------------------------------------", ANSI_GRAY));
        out.println(String.format("   Total Tasks Submitted       : %s", colorize(String.valueOf(stats.getTasksSubmitted()), ANSI_BOLD_WHITE)));
        out.println(String.format("   Total Tasks Completed       : %s", colorize(String.valueOf(stats.getTasksCompleted()), ANSI_BOLD_GREEN)));
        out.println(String.format("   Total Tasks Preempted       : %s", colorize(String.valueOf(stats.getTasksPreempted()), ANSI_BOLD_RED)));
        out.println(String.format("   Green-Gated BG Tasks        : %s (Waited for favorable grid carbon before running)",
                colorize(String.valueOf(stats.getBackgroundTasksGreenGated()), ANSI_BOLD_YELLOW)));
        out.println(String.format("   Tasks Failed                : %s",
                stats.getTasksFailed() == 0 ? colorize("0", ANSI_GREEN) : colorize(String.valueOf(stats.getTasksFailed()), ANSI_BOLD_RED)));
        out.println(colorize("------------------------------------------------------------------------------------------", ANSI_GRAY));
        out.println(colorize("   Execution Status            : " + (stats.isAllSuccess()
                ? "ALL WORKLOADS COMPLETED CLEANLY - LIFECYCLE & CARBON GATING VERIFIED"
                : "COMPLETED WITH ANOMALIES"), stats.isAllSuccess() ? ANSI_BOLD_GREEN : ANSI_BOLD_RED));
        out.println(colorize("==========================================================================================", ANSI_BOLD_CYAN));
        out.println();
    }

    private String getIntensityLevel(double intensity) {
        if (intensity < 120.0) {
            return "LOW / GREEN";
        } else if (intensity < 220.0) {
            return "MEDIUM";
        } else {
            return "HIGH / FOSSIL PEAK";
        }
    }

    public boolean isAnsiEnabled() {
        return ansiEnabled;
    }

    public void setAnsiEnabled(boolean ansiEnabled) {
        this.ansiEnabled = ansiEnabled;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public void setStartTimeMillis(long startTimeMillis) {
        this.startTimeMillis = startTimeMillis;
    }
}

