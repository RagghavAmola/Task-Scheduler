package com.greencloud.scheduler;

import com.greencloud.env.EnvironmentalDataProvider;
import com.greencloud.exceptions.EnvironmentalDataException;
import com.greencloud.model.ComputeTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.logging.Logger;

/**
 * TaskScheduler manages submission and single-task execution of ComputeTasks,
 * prioritizing tasks based on priority level and submission timestamp while enforcing
 * carbon intensity thresholds.
 */
public class TaskScheduler {

    private static final Logger LOGGER = Logger.getLogger(TaskScheduler.class.getName());

    private final PriorityQueue<ComputeTask> pendingQueue;

    public TaskScheduler() {
        this.pendingQueue = new PriorityQueue<>();
    }

    /**
     * Submits a compute task to the pending priority queue.
     *
     * @param task ComputeTask to schedule
     */
    public synchronized void submitTask(ComputeTask task) {
        if (task == null) {
            throw new IllegalArgumentException("Task cannot be null");
        }
        pendingQueue.add(task);
        LOGGER.info(() -> String.format("[SUBMIT] Task [%s] added (Priority: %s, Threshold: %.1f gCO2/kWh). Total pending: %d",
                task.getTaskId(), task.getPriority(), task.getCarbonThresholdGramsPerKwh(), pendingQueue.size()));
    }

    /**
     * Evaluates the top pending task against the current carbon intensity.
     * If eligible, removes the task from the queue and executes it.
     * If not eligible, leaves the task queued until grid carbon conditions improve.
     *
     * @param currentCarbonIntensity current grid carbon intensity in gCO2/kWh
     * @return the executed ComputeTask, or null if no task was executed
     */
    public synchronized ComputeTask tick(double currentCarbonIntensity) {
        ComputeTask candidate = pendingQueue.peek();
        if (candidate == null) {
            LOGGER.fine("[TICK] No pending tasks in queue.");
            return null;
        }

        LOGGER.info(() -> String.format("[TICK] Evaluating top task [%s] (Priority: %s, Threshold: %.1f gCO2/kWh) against current carbon intensity: %.1f gCO2/kWh",
                candidate.getTaskId(), candidate.getPriority(), candidate.getCarbonThresholdGramsPerKwh(), currentCarbonIntensity));

        if (candidate.isEligibleToRun(currentCarbonIntensity)) {
            pendingQueue.poll();
            LOGGER.info(() -> String.format("[DECISION] Task [%s] IS ELIGIBLE to run. Starting execution...", candidate.getTaskId()));
            try {
                candidate.execute();
                LOGGER.info(() -> String.format("[OUTCOME] Task [%s] finished execution with status: %s", candidate.getTaskId(), candidate.getStatus()));
            } catch (Exception e) {
                LOGGER.severe(() -> String.format("[OUTCOME] Task [%s] failed with exception: %s", candidate.getTaskId(), e.getMessage()));
            }
            return candidate;
        } else {
            LOGGER.info(() -> String.format("[DECISION] Task [%s] IS NOT ELIGIBLE to run (Threshold: %.1f gCO2/kWh < Current: %.1f gCO2/kWh). Retaining in queue for better conditions.",
                    candidate.getTaskId(), candidate.getCarbonThresholdGramsPerKwh(), currentCarbonIntensity));
            return null;
        }
    }

    /**
     * Overloaded tick method that obtains current carbon intensity from an EnvironmentalDataProvider.
     *
     * @param provider EnvironmentalDataProvider
     * @return executed ComputeTask, or null
     * @throws EnvironmentalDataException if fetching intensity fails
     */
    public synchronized ComputeTask tick(EnvironmentalDataProvider provider) throws EnvironmentalDataException {
        if (provider == null) {
            throw new IllegalArgumentException("EnvironmentalDataProvider cannot be null");
        }
        double currentIntensity = provider.getCurrentIntensity().carbonIntensityGCO2kWh();
        return tick(currentIntensity);
    }

    /**
     * Returns a snapshot list of current pending tasks in priority order.
     *
     * @return List of pending ComputeTasks
     */
    public synchronized List<ComputeTask> getPendingTasks() {
        List<ComputeTask> list = new ArrayList<>(pendingQueue);
        Collections.sort(list);
        return list;
    }

    /**
     * Returns the total count of pending tasks in the queue.
     *
     * @return pending task count
     */
    public synchronized int getPendingTaskCount() {
        return pendingQueue.size();
    }
}
