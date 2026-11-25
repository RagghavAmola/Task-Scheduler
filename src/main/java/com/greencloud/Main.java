package com.greencloud;

import com.greencloud.simulation.SimulationEngine;
import com.greencloud.simulation.SimulationStats;
import com.greencloud.ui.GreenCloudDashboardApp;

/**
 * Main application entry point for the Green Cloud Carbon-Aware Task Scheduler.
 * Executes the end-to-end simulation demonstration for live demonstration,
 * or launches the JavaFX desktop dashboard when requested via CLI flags.
 */
public class Main {

    public static void main(String[] args) {
        boolean launchUiDirectly = false;
        boolean launchUiAfterConsole = false;
        int durationSeconds = SimulationEngine.DEFAULT_DURATION_SECONDS;

        if (args != null && args.length > 0) {
            for (String arg : args) {
                if ("--ui".equalsIgnoreCase(arg) || "-ui".equalsIgnoreCase(arg) || "--gui".equalsIgnoreCase(arg)) {
                    launchUiDirectly = true;
                } else if ("--show-ui".equalsIgnoreCase(arg) || "--launch-ui".equalsIgnoreCase(arg)) {
                    launchUiAfterConsole = true;
                } else {
                    try {
                        durationSeconds = Integer.parseInt(arg);
                    } catch (NumberFormatException ignored) {
                        // Ignore non-numeric flags
                    }
                }
            }
        }

        if (launchUiDirectly) {
            GreenCloudDashboardApp.launchApp(args);
            return;
        }

        SimulationEngine engine = new SimulationEngine(durationSeconds);
        SimulationStats stats = engine.run();

        if (launchUiAfterConsole) {
            GreenCloudDashboardApp.launchApp(args);
        } else {
            System.exit(stats.isAllSuccess() ? 0 : 1);
        }
    }
}
