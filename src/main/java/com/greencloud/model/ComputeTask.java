package com.greencloud.model;

import java.util.Objects;

/**
 * Abstract base class representing a compute task in the Green Cloud Scheduler.
 * Implements Comparable to allow natural ordering in PriorityQueue based on
 * priority (CRITICAL > HIGH > BACKGROUND) and submission timestamp (FIFO for same priority).
 */
public abstract class ComputeTask implements Comparable<ComputeTask> {

    private final String taskId;
    private TaskPriority priority;
    private long durationMillis;
    private double carbonThresholdGramsPerKwh;
    private long submittedAt;
    private TaskStatus status;

    public ComputeTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh) {
        this(taskId, priority, durationMillis, carbonThresholdGramsPerKwh, System.currentTimeMillis(), TaskStatus.PENDING);
    }

    public ComputeTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh, long submittedAt) {
        this(taskId, priority, durationMillis, carbonThresholdGramsPerKwh, submittedAt, TaskStatus.PENDING);
    }

    public ComputeTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh, long submittedAt, TaskStatus status) {
        this.taskId = taskId;
        this.priority = priority;
        this.durationMillis = durationMillis;
        this.carbonThresholdGramsPerKwh = carbonThresholdGramsPerKwh;
        this.submittedAt = submittedAt;
        this.status = status;
    }

    /**
     * Abstract method to simulate execution of the compute task.
     * Concrete subclasses provide their specific workload implementation.
     */
    public abstract void execute();

    /**
     * Checks if this task is eligible to run based on current carbon intensity.
     * CRITICAL priority tasks are always eligible, whereas other tasks require
     * the current carbon intensity to be at or below their carbon threshold.
     *
     * @param currentCarbonIntensity current environmental carbon intensity in gCO2/kWh
     * @return true if eligible to run, false otherwise
     */
    public boolean isEligibleToRun(double currentCarbonIntensity) {
        return this.priority == TaskPriority.CRITICAL || currentCarbonIntensity <= this.carbonThresholdGramsPerKwh;
    }

    public String getTaskId() {
        return taskId;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public double getCarbonThresholdGramsPerKwh() {
        return carbonThresholdGramsPerKwh;
    }

    public void setCarbonThresholdGramsPerKwh(double carbonThresholdGramsPerKwh) {
        this.carbonThresholdGramsPerKwh = carbonThresholdGramsPerKwh;
    }

    public long getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(long submittedAt) {
        this.submittedAt = submittedAt;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    /**
     * Compares two tasks for natural ordering in a PriorityQueue.
     * Higher priority tasks come first (CRITICAL < HIGH < BACKGROUND in enum ordinal comparison).
     * If priorities are equal, earlier submitted tasks come first (submittedAt ascending).
     */
    @Override
    public int compareTo(ComputeTask other) {
        if (other == null) {
            return -1;
        }
        if (this.priority != other.priority) {
            if (this.priority == null) return 1;
            if (other.priority == null) return -1;
            return this.priority.compareTo(other.priority);
        }
        int timeCompare = Long.compare(this.submittedAt, other.submittedAt);
        if (timeCompare != 0) {
            return timeCompare;
        }
        if (this.taskId != null && other.taskId != null) {
            return this.taskId.compareTo(other.taskId);
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ComputeTask that = (ComputeTask) o;
        return durationMillis == that.durationMillis &&
                Double.compare(that.carbonThresholdGramsPerKwh, carbonThresholdGramsPerKwh) == 0 &&
                submittedAt == that.submittedAt &&
                Objects.equals(taskId, that.taskId) &&
                priority == that.priority;
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, priority, durationMillis, carbonThresholdGramsPerKwh, submittedAt);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "taskId='" + taskId + '\'' +
                ", priority=" + priority +
                ", durationMillis=" + durationMillis +
                ", carbonThresholdGramsPerKwh=" + carbonThresholdGramsPerKwh +
                ", submittedAt=" + submittedAt +
                ", status=" + status +
                '}';
    }
}
