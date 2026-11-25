package com.greencloud.ui;

import com.greencloud.model.BackupTask;
import com.greencloud.model.BatchProcessingTask;
import com.greencloud.model.ComputeTask;
import com.greencloud.model.TaskPriority;
import com.greencloud.simulation.SimulationEngine.ScheduledTaskSubmission;

/**
 * Representation of a task configured in the pre-run launcher before simulation starts.
 * Immutable and supports conversion to {@link ScheduledTaskSubmission} and {@link ComputeTask}.
 */
public record StagedTask(
        String taskType,
        String taskId,
        TaskPriority priority,
        long durationMillis,
        double carbonThresholdGramsPerKwh,
        long arrivalDelayMillis,
        String sourcePath,
        String destinationPath,
        int recordCount
) {
    public StagedTask {
        taskType = (taskType != null && !taskType.isBlank()) ? taskType : "BackupTask";
        taskId = (taskId != null && !taskId.isBlank()) ? taskId : ("task-" + System.nanoTime() % 10000);
        priority = (priority != null) ? priority : TaskPriority.BACKGROUND;
        durationMillis = Math.max(50, durationMillis);
        carbonThresholdGramsPerKwh = Math.max(0.0, carbonThresholdGramsPerKwh);
        arrivalDelayMillis = Math.max(0, arrivalDelayMillis);
        sourcePath = (sourcePath != null) ? sourcePath : "/data/source";
        destinationPath = (destinationPath != null) ? destinationPath : "/data/backup";
        recordCount = Math.max(1, recordCount);
    }

    public String getTaskType() { return taskType; }
    public String getTaskId() { return taskId; }
    public TaskPriority getPriority() { return priority; }
    public long getDurationMillis() { return durationMillis; }
    public double getCarbonThresholdGramsPerKwh() { return carbonThresholdGramsPerKwh; }
    public long getArrivalDelayMillis() { return arrivalDelayMillis; }
    public String getSourcePath() { return sourcePath; }
    public String getDestinationPath() { return destinationPath; }
    public int getRecordCount() { return recordCount; }

    public ComputeTask createComputeTask() {
        if ("BatchProcessingTask".equalsIgnoreCase(taskType)) {
            return new BatchProcessingTask(taskId, priority, durationMillis, carbonThresholdGramsPerKwh, recordCount);
        } else {
            return new BackupTask(taskId, priority, durationMillis, carbonThresholdGramsPerKwh, sourcePath, destinationPath);
        }
    }

    public ScheduledTaskSubmission toSubmission() {
        return new ScheduledTaskSubmission(createComputeTask(), arrivalDelayMillis);
    }
}
