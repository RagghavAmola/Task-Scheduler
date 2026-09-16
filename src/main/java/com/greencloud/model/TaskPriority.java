package com.greencloud.model;

/**
 * Priority levels for compute tasks in the Green Cloud Scheduler.
 * Tasks are ordered by priority: CRITICAL > HIGH > BACKGROUND.
 */
public enum TaskPriority {
    CRITICAL,
    HIGH,
    BACKGROUND
}
