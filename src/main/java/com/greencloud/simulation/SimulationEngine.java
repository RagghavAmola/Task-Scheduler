package com.greencloud.simulation;

import com.greencloud.env.CarbonIntensitySnapshot;
import com.greencloud.env.EnvironmentalDataProvider;
import com.greencloud.env.SimulatedEnvironmentalDataProvider;
import com.greencloud.model.BackupTask;
import com.greencloud.model.BatchProcessingTask;
import com.greencloud.model.ComputeTask;
import com.greencloud.model.TaskPriority;
import com.greencloud.model.TaskStatus;
import com.greencloud.pool.TaskObjectPool;
import com.greencloud.scheduler.TaskScheduler;
import com.greencloud.view.ConsoleView;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SimulationEngine runs an end-to-end demonstration of the Green Cloud Carbon-Aware Task Scheduler.
 * <p>
 * Key Capabilities:
 * <ol>
 *   <li>Simulates dynamic grid carbon oscillations across low, medium, and high intensity over a configurable duration.</li>
 *   <li>Submits a realistic workload mix: BACKGROUND tasks with varying carbon thresholds, HIGH priority task, and
 *       a CRITICAL task submitted mid-simulation while another task is executing.</li>
 *   <li>Executes scheduler ticks periodically via {@link ScheduledExecutorService}.</li>
 *   <li>Outputs a real-time, ANSI-colorized, boxed timeline via {@link ConsoleView} with elapsed-time markers.</li>
 *   <li>Outputs a styled summary block with submission, completion, preemption, and green-gating metrics.</li>
 * </ol>
 */
public class SimulationEngine {

    private static final Logger LOGGER = Logger.getLogger(SimulationEngine.class.getName());

    public static final int DEFAULT_DURATION_SECONDS = 60;
    public static final int DEFAULT_TICK_INTERVAL_MILLIS = 2000;

    private final int durationSeconds;
    private final int tickIntervalMillis;
    private final EnvironmentalDataProvider provider;
    private final ConsoleView consoleView;
    private final List<ComputeTask> submittedTasks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private boolean stopWhenAllCompleted = true;

    public SimulationEngine() {
        this(DEFAULT_DURATION_SECONDS, DEFAULT_TICK_INTERVAL_MILLIS, null, null);
    }

    public SimulationEngine(int durationSeconds) {
        this(durationSeconds, DEFAULT_TICK_INTERVAL_MILLIS, null, null);
    }

    public SimulationEngine(int durationSeconds, int tickIntervalMillis) {
        this(durationSeconds, tickIntervalMillis, null, null);
    }

    public SimulationEngine(int durationSeconds, int tickIntervalMillis, ConsoleView consoleView) {
        this(durationSeconds, tickIntervalMillis, null, consoleView);
    }

    public SimulationEngine(int durationSeconds, int tickIntervalMillis, EnvironmentalDataProvider provider, ConsoleView consoleView) {
        this.durationSeconds = (durationSeconds > 0) ? durationSeconds : DEFAULT_DURATION_SECONDS;
        this.tickIntervalMillis = (tickIntervalMillis > 0) ? tickIntervalMillis : DEFAULT_TICK_INTERVAL_MILLIS;
        this.consoleView = (consoleView != null) ? consoleView : new ConsoleView();

        // Oscillate with period = duration / 2 so at least 2 full waves occur during the simulation
        double period = Math.max(8.0, this.durationSeconds / 2.0);
        this.provider = (provider != null) ? provider : new SimulatedEnvironmentalDataProvider("SIMULATED-UK", 70.0, 310.0, period, true);
    }

    /**
     * Executes the end-to-end simulation demonstration.
     *
     * @return SimulationStats capturing metrics across the run
     */
    public SimulationStats run() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Simulation is already running");
        }

        SimulationStats stats = new SimulationStats();
        long simStartTime = System.currentTimeMillis();
        stats.setStartTimeMillis(simStartTime);
        consoleView.setStartTimeMillis(simStartTime);

        consoleView.printBanner(durationSeconds, tickIntervalMillis, provider.getClass().getSimpleName());

        TaskScheduler scheduler = new TaskScheduler(provider, null, consoleView);
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        AtomicInteger tickCounter = new AtomicInteger(0);
        AtomicBoolean allSubmissionsDispatched = new AtomicBoolean(false);

        // Schedule periodic ticks
        executor.scheduleWithFixedDelay(() -> {
            try {
                int currentTick = tickCounter.incrementAndGet();
                CarbonIntensitySnapshot snapshot = provider.getCurrentIntensity();
                consoleView.printTickHeader(currentTick, snapshot);

                scheduler.tick(provider);
                stats.recordTick();

                consoleView.printTickFooter(currentTick);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during simulation tick execution: " + e.getMessage(), e);
            }
        }, 150, tickIntervalMillis, TimeUnit.MILLISECONDS);

        // Schedule workload submissions
        scheduleWorkload(executor, scheduler, stats, allSubmissionsDispatched);

        // Simulation supervision loop
        long endTimeMax = simStartTime + (durationSeconds * 1000L);
        try {
            while (System.currentTimeMillis() < endTimeMax) {
                Thread.sleep(250);

                if (stopWhenAllCompleted && allSubmissionsDispatched.get() && !submittedTasks.isEmpty()) {
                    boolean allFinished = true;
                    List<String> events = scheduler.getExecutionEvents();
                    for (ComputeTask t : submittedTasks) {
                        boolean completed = t.getStatus() == TaskStatus.COMPLETED
                                || events.contains("COMPLETED:" + t.getTaskId())
                                || events.contains("CRITICAL_EXECUTED:" + t.getTaskId());
                        boolean failed = t.getStatus() == TaskStatus.FAILED || events.contains("FAILED:" + t.getTaskId());
                        if (!completed && !failed) {
                            allFinished = false;
                            break;
                        }
                    }
                    if (allFinished && scheduler.getPendingTaskCount() == 0 && scheduler.getCurrentlyRunningTask() == null) {
                        // Display 1 final idle tick
                        Thread.sleep(Math.min(tickIntervalMillis + 100, 2000));
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Simulation thread interrupted");
        } finally {
            // Clean shutdown of scheduled executors
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            stats.finish();
            tallyFinalMetrics(scheduler, stats);
            consoleView.printSummary(stats);
            running.set(false);
        }

        return stats;
    }
    /**
     * Schedules the arrival of tasks across the simulation timeline.
     * Ensures an active background task is running when the critical task arrives to demonstrate preemption.
     */
    private void scheduleWorkload(ScheduledExecutorService executor, TaskScheduler scheduler, SimulationStats stats, AtomicBoolean allDispatched) {
        // Scale task duration slightly for shorter runs so they don't instantly complete
        long durScale = (durationSeconds < 20) ? Math.max(200, durationSeconds * 50L) : 1200;

        // Task 1: Long-running background task with high threshold (submitted first so it begins executing)
        executor.schedule(() -> {
            ComputeTask t1 = new BackupTask("bg-heavy-archive", TaskPriority.BACKGROUND, Math.max(900, (long) (durScale * 2.5)), 350.0);
            submit(scheduler, t1, stats);
        }, 100, TimeUnit.MILLISECONDS);

        // Task 2: CRITICAL priority task submitted specifically while bg-heavy-archive is RUNNING
        executor.schedule(() -> {
            long waitStart = System.currentTimeMillis();
            while (scheduler.getCurrentlyRunningTask() == null && (System.currentTimeMillis() - waitStart) < 2500) {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ignored) {}
            }
            ComputeTask t2 = new BackupTask("crit-security-hotfix", TaskPriority.CRITICAL, Math.max(250, (long) (durScale * 0.7)), 50.0);
            submit(scheduler, t2, stats);
        }, Math.min(300, durationSeconds * 50L), TimeUnit.MILLISECONDS);

        // Task 3: HIGH priority task (threshold 220 gCO2/kWh) - proportional to duration (~15% into simulation)
        long delay3 = (long) (durationSeconds * 150.0);
        executor.schedule(() -> {
            ComputeTask t3 = new BackupTask("high-cache-indexing", TaskPriority.HIGH, durScale, 220.0);
            submit(scheduler, t3, stats);
        }, Math.max(500, delay3), TimeUnit.MILLISECONDS);

        // Task 4: Strict Green Background Task (requires low carbon < 110 gCO2/kWh) - proportional (~30% into simulation)
        long delay4 = (long) (durationSeconds * 300.0);
        executor.schedule(() -> {
            ComputeTask t4 = new BatchProcessingTask("bg-strict-audit", TaskPriority.BACKGROUND, durScale, 110.0, 100);
            submit(scheduler, t4, stats);
        }, Math.max(900, delay4), TimeUnit.MILLISECONDS);

        // Task 5: Moderate Background Task (threshold 180 gCO2/kWh) - proportional (~45% into simulation)
        long delay5 = (long) (durationSeconds * 450.0);
        executor.schedule(() -> {
            ComputeTask t5 = new BackupTask("bg-db-maintenance", TaskPriority.BACKGROUND, (long) (durScale * 0.9), 180.0);
            submit(scheduler, t5, stats);
        }, Math.max(1300, delay5), TimeUnit.MILLISECONDS);

        // Task 6: Lenient Background Task (threshold 280 gCO2/kWh) - proportional (~60% into simulation)
        long delay6 = (long) (durationSeconds * 600.0);
        executor.schedule(() -> {
            ComputeTask t6 = new BatchProcessingTask("bg-telemetry-sync", TaskPriority.BACKGROUND, durScale, 280.0, 250);
            submit(scheduler, t6, stats);
            allDispatched.set(true);
        }, Math.max(1700, delay6), TimeUnit.MILLISECONDS);
    }

    private void submit(TaskScheduler scheduler, ComputeTask task, SimulationStats stats) {
        try {
            submittedTasks.add(task);
            stats.recordSubmission();
            scheduler.submitTask(task);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to submit task [" + task.getTaskId() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * Compiles final verified metrics from scheduler events and task states.
     */
    private void tallyFinalMetrics(TaskScheduler scheduler, SimulationStats stats) {
        List<String> events = scheduler.getExecutionEvents();

        int completedCount = 0;
        int failedCount = 0;
        int greenGatedBgCount = 0;

        for (ComputeTask task : submittedTasks) {
            boolean isCompleted = task.getStatus() == TaskStatus.COMPLETED
                    || events.contains("COMPLETED:" + task.getTaskId())
                    || events.contains("CRITICAL_EXECUTED:" + task.getTaskId());

            if (isCompleted) {
                completedCount++;
                if (task.getPriority() == TaskPriority.BACKGROUND) {
                    greenGatedBgCount++;
                }
            } else if (task.getStatus() == TaskStatus.FAILED || events.contains("FAILED:" + task.getTaskId())) {
                failedCount++;
            }
        }

        int preemptionEvents = 0;
        for (String ev : events) {
            if (ev.startsWith("PREEMPTED:")) {
                preemptionEvents++;
            }
        }

        while (stats.getTasksCompleted() < completedCount) {
            stats.recordCompletion();
        }
        while (stats.getTasksPreempted() < preemptionEvents) {
            stats.recordPreemption();
        }
        while (stats.getBackgroundTasksGreenGated() < greenGatedBgCount) {
            stats.recordGreenGatedBackgroundTask();
        }
        while (stats.getTasksFailed() < failedCount) {
            stats.recordFailure();
        }
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public int getTickIntervalMillis() {
        return tickIntervalMillis;
    }

    public EnvironmentalDataProvider getProvider() {
        return provider;
    }

    public ConsoleView getConsoleView() {
        return consoleView;
    }

    public List<ComputeTask> getSubmittedTasks() {
        return Collections.unmodifiableList(submittedTasks);
    }

    public boolean isStopWhenAllCompleted() {
        return stopWhenAllCompleted;
    }

    public void setStopWhenAllCompleted(boolean stopWhenAllCompleted) {
        this.stopWhenAllCompleted = stopWhenAllCompleted;
    }
}
