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
        LOGGER.info(() -> String.format("[START] BatchProcessingTask [%s]: processing %d records (remaining duration: %d ms, total: %d ms)",
                getTaskId(), recordCount, getRemainingDurationMillis(), getDurationMillis()));
        setStatus(TaskStatus.RUNNING);

        while (getRemainingDurationMillis() > 0) {
            long totalRemainingBeforeChunk = getRemainingDurationMillis();
            long workDuration = Math.min(totalRemainingBeforeChunk, 100);
            long startTime = System.currentTimeMillis();

            try {
                if (workDuration > 0) {
                    Thread.sleep(workDuration);
                }
                // Successfully finished this chunk
                long remaining = Math.max(0, totalRemainingBeforeChunk - workDuration);
                setRemainingDurationMillis(remaining);
            } catch (InterruptedException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                long actualWorkDone = Math.min(workDuration, Math.max(0, elapsed));
                long remainingAfterInterruption = Math.max(0, totalRemainingBeforeChunk - actualWorkDone);
                setRemainingDurationMillis(remainingAfterInterruption);
                setStatus(TaskStatus.PREEMPTED);
                LOGGER.warning(() -> String.format("[PREEMPTED] BatchProcessingTask [%s] was interrupted. Remaining duration: %d ms",
                        getTaskId(), remainingAfterInterruption));
                Thread.currentThread().interrupt();
                return; // Exit execution immediately when preempted/interrupted
            }
        }

        // Work completed with 0 remaining duration
        setStatus(TaskStatus.COMPLETED);
        LOGGER.info(() -> String.format("[END] BatchProcessingTask [%s]: completed successfully", getTaskId()));
    }
}