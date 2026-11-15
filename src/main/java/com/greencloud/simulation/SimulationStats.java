package com.greencloud.simulation;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Data holder capturing quantitative metrics and execution statistics
 * across an end-to-end simulation run.
 */
public class SimulationStats {

    private final AtomicInteger tasksSubmitted = new AtomicInteger(0);
    private final AtomicInteger tasksCompleted = new AtomicInteger(0);
    private final AtomicInteger tasksPreempted = new AtomicInteger(0);
    private final AtomicInteger backgroundTasksGreenGated = new AtomicInteger(0);
    private final AtomicInteger tasksFailed = new AtomicInteger(0);
    private final AtomicInteger ticksExecuted = new AtomicInteger(0);

    private volatile long startTimeMillis;
    private volatile long endTimeMillis;

    public SimulationStats() {
        this.startTimeMillis = System.currentTimeMillis();
    }

    public void recordSubmission() {
        tasksSubmitted.incrementAndGet();
    }

    public void recordCompletion() {
        tasksCompleted.incrementAndGet();
    }

    public void recordPreemption() {
        tasksPreempted.incrementAndGet();
    }

    public void recordGreenGatedBackgroundTask() {
        backgroundTasksGreenGated.incrementAndGet();
    }

    public void recordFailure() {
        tasksFailed.incrementAndGet();
    }

    public void recordTick() {
        ticksExecuted.incrementAndGet();
    }

    public void finish() {
        this.endTimeMillis = System.currentTimeMillis();
    }

    public int getTasksSubmitted() {
        return tasksSubmitted.get();
    }

    public int getTasksCompleted() {
        return tasksCompleted.get();
    }

    public int getTasksPreempted() {
        return tasksPreempted.get();
    }

    public int getBackgroundTasksGreenGated() {
        return backgroundTasksGreenGated.get();
    }

    public int getTasksFailed() {
        return tasksFailed.get();
    }

    public int getTicksExecuted() {
        return ticksExecuted.get();
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    void setStartTimeMillis(long startTimeMillis) {
        this.startTimeMillis = startTimeMillis;
    }

    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    public double getTotalDurationSeconds() {
        long end = (endTimeMillis > 0) ? endTimeMillis : System.currentTimeMillis();
        return Math.max(0.0, (end - startTimeMillis) / 1000.0);
    }

    public boolean isAllSuccess() {
        return tasksCompleted.get() == tasksSubmitted.get() && tasksFailed.get() == 0;
    }

    @Override
    public String toString() {
        return String.format("SimulationStats[submitted=%d, completed=%d, preempted=%d, greenGated=%d, failed=%d, ticks=%d, duration=%.1fs]",
                getTasksSubmitted(), getTasksCompleted(), getTasksPreempted(),
                getBackgroundTasksGreenGated(), getTasksFailed(), getTicksExecuted(), getTotalDurationSeconds());
    }
}

