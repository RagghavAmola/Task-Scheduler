package com.greencloud.simulation;

import com.greencloud.env.CarbonIntensitySnapshot;
import com.greencloud.env.EnvironmentalDataProvider;
import com.greencloud.env.SimulatedEnvironmentalDataProvider;
import com.greencloud.metrics.MetricsCollector;
import com.greencloud.model.BackupTask;
import com.greencloud.model.BatchProcessingTask;
import com.greencloud.model.ComputeTask;
import com.greencloud.model.TaskPriority;
import com.greencloud.model.TaskStatus;
import com.greencloud.scheduler.TaskScheduler;
import com.greencloud.view.ConsoleView;

import java.util.ArrayList;
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
 *       a CRITICAL task submitted mid-simulation while another task is executing (or custom pre-staged tasks).</li>
 *   <li>Executes scheduler ticks periodically via {@link ScheduledExecutorService}.</li>
 *   <li>Outputs a real-time, ANSI-colorized, boxed timeline via {@link ConsoleView} with elapsed-time markers.</li>
 *   <li>Outputs a styled summary block with submission, completion, preemption, and green-gating metrics.</li>
 *   <li>Persists execution results to {@code results/events.csv} and {@code results/summary.csv} via {@link MetricsCollector}.</li>
 * </ol>
 */
public class SimulationEngine {

    private static final Logger LOGGER = Logger.getLogger(SimulationEngine.class.getName());

    public static final int DEFAULT_DURATION_SECONDS = 60;
    public static final int DEFAULT_TICK_INTERVAL_MILLIS = 2000;

    /**
     * Immutable specification for a task scheduled to be submitted at an arrival delay offset.
     */
    public record ScheduledTaskSubmission(ComputeTask task, long arrivalDelayMillis) {}

    /**
     * Callback interface for observing real-time simulation progress without polling.
     */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int currentTick, long elapsedMillis, long remainingMillis, int completedCount, int totalCount);
    }

    private final int durationSeconds;
    private final int tickIntervalMillis;
    private final EnvironmentalDataProvider provider;
    private final ConsoleView consoleView;
    private final MetricsCollector metricsCollector;
    private final List<ScheduledTaskSubmission> customWorkload;
    private final List<ComputeTask> submittedTasks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private boolean stopWhenAllCompleted = true;
    private volatile ProgressListener progressListener;

    public SimulationEngine() {
        this(DEFAULT_DURATION_SECONDS, DEFAULT_TICK_INTERVAL_MILLIS, null, null, null);
    }

    public SimulationEngine(int durationSeconds) {
        this(durationSeconds, DEFAULT_TICK_INTERVAL_MILLIS, null, null, null);
    }

    public SimulationEngine(int durationSeconds, int tickIntervalMillis) {
        this(durationSeconds, tickIntervalMillis, null, null, null);
    }

    public SimulationEngine(int durationSeconds, int tickIntervalMillis, ConsoleView consoleView) {
        this(durationSeconds, tickIntervalMillis, null, consoleView, null);
    }

    public SimulationEngine(int durationSeconds, int tickIntervalMillis, EnvironmentalDataProvider provider, ConsoleView consoleView) {
        this(durationSeconds, tickIntervalMillis, provider, consoleView, null);
    }

    public SimulationEngine(int durationSeconds, int tickIntervalMillis, List<ScheduledTaskSubmission> customWorkload) {
        this(durationSeconds, tickIntervalMillis, null, null, customWorkload);
    }

    public SimulationEngine(int durationSeconds, int tickIntervalMillis, EnvironmentalDataProvider provider,
                            ConsoleView consoleView, List<ScheduledTaskSubmission> customWorkload) {
        this.durationSeconds = (durationSeconds > 0) ? durationSeconds : DEFAULT_DURATION_SECONDS;
        this.tickIntervalMillis = (tickIntervalMillis > 0) ? tickIntervalMillis : DEFAULT_TICK_INTERVAL_MILLIS;
        this.consoleView = (consoleView != null) ? consoleView : new ConsoleView();
        this.metricsCollector = new MetricsCollector();
        this.customWorkload = (customWorkload != null) ? new ArrayList<>(customWorkload) : null;

        // Oscillate with period = duration / 2 so at least 2 full waves occur during the simulation
        double period = Math.max(8.0, this.durationSeconds / 2.0);
        this.provider = (provider != null) ? provider : new SimulatedEnvironmentalDataProvider("SIMULATED-UK", 70.0, 310.0, period, true);
    }

    public void setProgressListener(ProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    public ProgressListener getProgressListener() {
        return progressListener;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
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
        long endTimeMax = simStartTime + (durationSeconds * 1000L);
        stats.setStartTimeMillis(simStartTime);
        consoleView.setStartTimeMillis(simStartTime);

        consoleView.printBanner(durationSeconds, tickIntervalMillis, provider.getClass().getSimpleName());

        TaskScheduler scheduler = new TaskScheduler(provider, null, consoleView, metricsCollector);
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        AtomicInteger tickCounter = new AtomicInteger(0);
        AtomicBoolean allSubmissionsDispatched = new AtomicBoolean(false);

        // Schedule periodic ticks
        executor.scheduleWithFixedDelay(() -> {
            try {
                int currentTick = tickCounter.incrementAndGet();
                CarbonIntensitySnapshot snapshot = provider.getCurrentIntensity();
                metricsCollector.setCurrentCarbonIntensity(snapshot.carbonIntensityGCO2kWh());
                consoleView.printTickHeader(currentTick, snapshot);

                scheduler.tick(provider);
                stats.recordTick();

                consoleView.printTickFooter(currentTick);

                // Notify lightweight progress listener
                if (progressListener != null) {
                    long now = System.currentTimeMillis();
                    long elapsed = now - simStartTime;
                    long remaining = Math.max(0, endTimeMax - now);
                    int completed = (int) submittedTasks.stream()
                            .filter(t -> t.getStatus() == TaskStatus.COMPLETED || t.getStatus() == TaskStatus.FAILED)
                            .count();
                    int total = (customWorkload != null && !customWorkload.isEmpty()) ? customWorkload.size() : Math.max(submittedTasks.size(), 6);
                    progressListener.onProgress(currentTick, elapsed, remaining, completed, total);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during simulation tick execution: " + e.getMessage(), e);
            }
        }, 150, tickIntervalMillis, TimeUnit.MILLISECONDS);

        // Schedule workload submissions
        scheduleWorkload(executor, scheduler, stats, allSubmissionsDispatched);

        // Simulation supervision loop
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

            // Persist post-run metrics to results/events.csv and results/summary.csv
            try {
                metricsCollector.exportResults("results");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to export results CSV files to results/ directory: " + e.getMessage(), e);
            }

            // Final progress update
            if (progressListener != null) {
                long now = System.currentTimeMillis();
                long elapsed = now - simStartTime;
                int completed = (int) submittedTasks.stream()
                        .filter(t -> t.getStatus() == TaskStatus.COMPLETED || t.getStatus() == TaskStatus.FAILED)
                        .count();
                progressListener.onProgress(tickCounter.get(), elapsed, 0L, completed, submittedTasks.size());
            }

            running.set(false);
        }

        return stats;
    }

    /**
     * Schedules the arrival of tasks across the simulation timeline.
     * Uses custom pre-staged tasks if provided, otherwise dispatches the standard 6-task realistic mix.
     */
    private void scheduleWorkload(ScheduledExecutorService executor, TaskScheduler scheduler, SimulationStats stats, AtomicBoolean allDispatched) {
        if (customWorkload != null && !customWorkload.isEmpty()) {
            long maxDelay = 0;
            for (ScheduledTaskSubmission item : customWorkload) {
                long delay = Math.max(0, item.arrivalDelayMillis());
                if (delay > maxDelay) {
                    maxDelay = delay;
                }
                executor.schedule(() -> {
                    item.task().setSubmittedAt(System.currentTimeMillis());
                    submit(scheduler, item.task(), stats);
                }, delay, TimeUnit.MILLISECONDS);
            }
            // All custom submissions scheduled; mark dispatched after the max arrival delay
            executor.schedule(() -> allDispatched.set(true), maxDelay + 100, TimeUnit.MILLISECONDS);
            return;
        }

        // Standard realistic workload mix
        long durScale = (durationSeconds < 20) ? Math.max(200, durationSeconds * 50L) : 1200;

        // Task 1: Long-running background task with high threshold
        executor.schedule(() -> {
            ComputeTask t1 = new BackupTask("bg-heavy-archive", TaskPriority.BACKGROUND, Math.max(900, (long) (durScale * 2.5)), 350.0);
            submit(scheduler, t1, stats);
        }, 100, TimeUnit.MILLISECONDS);

        // Task 2: CRITICAL priority task submitted while bg-heavy-archive is RUNNING
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

        // Task 3: HIGH priority task (threshold 220 gCO2/kWh)
        long delay3 = (long) (durationSeconds * 150.0);
        executor.schedule(() -> {
            ComputeTask t3 = new BackupTask("high-cache-indexing", TaskPriority.HIGH, durScale, 220.0);
            submit(scheduler, t3, stats);
        }, Math.max(500, delay3), TimeUnit.MILLISECONDS);

        // Task 4: Strict Green Background Task (threshold 110 gCO2/kWh)
        long delay4 = (long) (durationSeconds * 300.0);
        executor.schedule(() -> {
            ComputeTask t4 = new BatchProcessingTask("bg-strict-audit", TaskPriority.BACKGROUND, durScale, 110.0, 100);
            submit(scheduler, t4, stats);
        }, Math.max(900, delay4), TimeUnit.MILLISECONDS);

        // Task 5: Moderate Background Task (threshold 180 gCO2/kWh)
        long delay5 = (long) (durationSeconds * 450.0);
        executor.schedule(() -> {
            ComputeTask t5 = new BackupTask("bg-db-maintenance", TaskPriority.BACKGROUND, (long) (durScale * 0.9), 180.0);
            submit(scheduler, t5, stats);
        }, Math.max(1300, delay5), TimeUnit.MILLISECONDS);

        // Task 6: Lenient Background Task (threshold 280 gCO2/kWh)
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
            metricsCollector.registerTaskThreshold(task.getTaskId(), task.getCarbonThresholdGramsPerKwh());
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
