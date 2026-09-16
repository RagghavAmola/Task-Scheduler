package com.greencloud.model;

import java.util.logging.Logger;

/**
 * Concrete implementation of ComputeTask representing a batch data processing workload.
 */
public class BatchProcessingTask extends ComputeTask {

    private static final Logger LOGGER = Logger.getLogger(BatchProcessingTask.class.getName());
    private int recordCount;

    public BatchProcessingTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh) {
        super(taskId, priority, durationMillis, carbonThresholdGramsPerKwh);
    }

    public BatchProcessingTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh, long submittedAt) {
        super(taskId, priority, durationMillis, carbonThresholdGramsPerKwh, submittedAt);
    }

    public BatchProcessingTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh, int recordCount) {
        super(taskId, priority, durationMillis, carbonThresholdGramsPerKwh);
        this.recordCount = recordCount;
    }

    public BatchProcessingTask(String taskId, TaskPriority priority, long durationMillis, double carbonThresholdGramsPerKwh, long submittedAt, int recordCount) {
        super(taskId, priority, durationMillis, carbonThresholdGramsPerKwh, submittedAt);
        this.recordCount = recordCount;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(int recordCount) {
        this.recordCount = recordCount;
    }

    @Override
    public void execute() {
        LOGGER.info(() -> String.format("[START] BatchProcessingTask [%s]: processing %d records (duration: %d ms)",
                getTaskId(), recordCount, getDurationMillis()));
        setStatus(TaskStatus.RUNNING);
        try {
            long workDuration = Math.min(getDurationMillis(), 100);
            if (workDuration > 0) {
                Thread.sleep(workDuration);
            }
            setStatus(TaskStatus.COMPLETED);
            LOGGER.info(() -> String.format("[END] BatchProcessingTask [%s]: completed successfully", getTaskId()));
        } catch (InterruptedException e) {
            setStatus(TaskStatus.PREEMPTED);
            LOGGER.warning(() -> String.format("[PREEMPTED] BatchProcessingTask [%s] was interrupted", getTaskId()));
            Thread.currentThread().interrupt();
        }
    }
}
