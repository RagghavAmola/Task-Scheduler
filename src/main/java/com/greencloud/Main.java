package com.greencloud;

import com.greencloud.simulation.SimulationEngine;
import com.greencloud.simulation.SimulationStats;

/**
 * Main application entry point for the Green Cloud Carbon-Aware Task Scheduler.
 * Executes the end-to-end simulation demonstration for live demonstration.
 */
public class Main {

    public static void main(String[] args) {
        int durationSeconds = SimulationEngine.DEFAULT_DURATION_SECONDS;

        if (args != null && args.length > 0) {
            try {
                durationSeconds = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid duration argument '" + args[0] + "'. Using default " + SimulationEngine.DEFAULT_DURATION_SECONDS + " seconds.");
            }
        }

        SimulationEngine engine = new SimulationEngine(durationSeconds);
        SimulationStats stats = engine.run();

        System.exit(stats.isAllSuccess() ? 0 : 1);
    }
}
