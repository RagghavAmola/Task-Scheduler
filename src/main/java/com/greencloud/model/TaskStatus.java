package com.greencloud.model;

/**
 * Execution status for compute tasks in the Green Cloud Scheduler.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    PREEMPTED,
    COMPLETED
}
