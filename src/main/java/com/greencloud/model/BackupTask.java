package com.greencloud.model;

import java.util.logging.Logger;

/**
 * Concrete implementation of ComputeTask representing a system backup operation.
 */
public class BackupTask extends ComputeTask {

    private static final Logger LOGGER = Logger.getLogger(BackupTask.class.getName());
    private String sourcePath;
    private String destinationPath;

    public BackupTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh) {
        super(taskId, priority, durationMillis, carbonThresholdGramsPerKwh);
    }

    public BackupTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh, long submittedAt) {
        super(taskId, priority, durationMillis, carbonThresholdGramsPerKwh, submittedAt);
    }

    public BackupTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh, String sourcePath, String destinationPath) {
        super(taskId, priority, durationMillis, carbonThresholdGramsPerKwh);
        this.sourcePath = sourcePath;
        this.destinationPath = destinationPath;
    }

    public BackupTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh, long submittedAt, String sourcePath, String destinationPath) {
        super(taskId, priority, durationMillis, carbonThresholdGramsPerKwh, submittedAt);
        this.sourcePath = sourcePath;
        this.destinationPath = destinationPath;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getDestinationPath() {
        return destinationPath;
    }

    public void setDestinationPath(String destinationPath) {
        this.destinationPath = destinationPath;
    }

    @Override
    public void execute() {
        LOGGER.info(() -> String.format("[START] BackupTask [%s]: backing up from '%s' to '%s' (remaining duration: %d ms, total: %d ms)",
                getTaskId(), sourcePath != null ? sourcePath : "default_src", destinationPath != null ? destinationPath : "default_dest", getRemainingDurationMillis(), getDurationMillis()));
        setStatus(TaskStatus.RUNNING);
        long workDuration = getRemainingDurationMillis();
        long startTime = System.currentTimeMillis();
        try {
            if (workDuration > 0) {
                Thread.sleep(workDuration);
            }
            setRemainingDurationMillis(0);
            setStatus(TaskStatus.COMPLETED);
            LOGGER.info(() -> String.format("[END] BackupTask [%s]: completed successfully", getTaskId()));
        } catch (InterruptedException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            long remaining = Math.max(0, workDuration - elapsed);
            setRemainingDurationMillis(remaining);
            setStatus(TaskStatus.PREEMPTED);
            LOGGER.warning(() -> String.format("[PREEMPTED] BackupTask [%s] was interrupted. Remaining duration: %d ms", getTaskId(), remaining));
            Thread.currentThread().interrupt();
        }
    }
}
