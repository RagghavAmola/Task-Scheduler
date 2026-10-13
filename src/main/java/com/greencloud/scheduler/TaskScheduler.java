package com.greencloud.scheduler;

import com.greencloud.env.EnvironmentalDataProvider;
import com.greencloud.exceptions.EnvironmentalDataException;
import com.greencloud.model.ComputeTask;
import com.greencloud.model.TaskPriority;
import com.greencloud.model.TaskStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * TaskScheduler manages submission and single-task execution of ComputeTasks,
 * prioritizing tasks based on priority level and submission timestamp while enforcing
 * carbon intensity thresholds. Supports preemption for CRITICAL priority tasks.
 */
public class TaskScheduler {

    private static final Logger LOGGER = Logger.getLogger(TaskScheduler.class.getName());

    private final PriorityQueue<ComputeTask> pendingQueue;
    private final Deque<ComputeTask> preemptedTaskStack;
    private final List<String> executionEvents;

    private volatile ComputeTask currentlyRunningTask;
    private volatile Thread currentExecutionThread;
    private volatile long taskExecutionStartTime;
    private volatile ComputeTask criticalTaskToPreempt;

    public TaskScheduler() {
        this.pendingQueue = new PriorityQueue<>();
        this.preemptedTaskStack = new ArrayDeque<>();
        this.executionEvents = new CopyOnWriteArrayList<>();
    }

    /**
     * Submits a compute task to the pending priority queue.
     * If a CRITICAL task is submitted while another task is currently executing,
     * triggers preemption of the running task so that the critical task can execute immediately.
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

        if (task.getPriority() == TaskPriority.CRITICAL && currentlyRunningTask != null && currentlyRunningTask.getStatus() == TaskStatus.RUNNING) {
            LOGGER.info(() -> String.format("[PREEMPTION TRIGGER] Critical task [%s] submitted while task [%s] is RUNNING. Interrupting executing thread.",
                    task.getTaskId(), currentlyRunningTask.getTaskId()));
            criticalTaskToPreempt = task;
            if (currentExecutionThread != null) {
                currentExecutionThread.interrupt();
            }
        }
    }

    /**
     * Executes a critical task with preemption semantics:
     * 1. If a background task is currently RUNNING, pause it: save its remaining work/duration,
     *    set status to PREEMPTED, and push it onto the stack.
     * 2. Immediately execute the critical task.
     * 3. After the critical task completes, pop the stack and resume the preempted task
     *    from its saved context (status back to RUNNING, then COMPLETED).
     *
     * @param criticalTask the CRITICAL priority ComputeTask to execute immediately
     * @return the executed critical task
     */
    public synchronized ComputeTask executeCritical(ComputeTask criticalTask) {
        if (criticalTask == null) {
            throw new IllegalArgumentException("Critical task cannot be null");
        }

        pendingQueue.remove(criticalTask);

        // 1. If a background task is currently RUNNING, pause it: save remaining work/duration,
        // set status to PREEMPTED, push it onto the stack
        ComputeTask runningTask = currentlyRunningTask;
        if (runningTask != null && runningTask != criticalTask) {
            if (runningTask.getStatus() == TaskStatus.RUNNING) {
                long elapsed = Math.max(0, System.currentTimeMillis() - taskExecutionStartTime);
                long remaining = Math.max(0, runningTask.getRemainingDurationMillis() - elapsed);
                runningTask.setRemainingDurationMillis(remaining);
                runningTask.setStatus(TaskStatus.PREEMPTED);
                if (currentExecutionThread != null && currentExecutionThread != Thread.currentThread()) {
                    currentExecutionThread.interrupt();
                }
            }
            if (!preemptedTaskStack.contains(runningTask)) {
                preemptedTaskStack.push(runningTask);
                runningTask.setPreemptedTime(System.currentTimeMillis());
                executionEvents.add("PREEMPTED:" + runningTask.getTaskId());
                LOGGER.info(() -> String.format("[PREEMPTION] Task [%s] preempted at %.1f%% completion (remaining work: %d ms)",
                        runningTask.getTaskId(), runningTask.getCompletionPercentage(), runningTask.getRemainingDurationMillis()));
            }
        }

        // 2. Immediately execute the critical task
        currentlyRunningTask = criticalTask;
        currentExecutionThread = Thread.currentThread();
        taskExecutionStartTime = System.currentTimeMillis();
        criticalTask.setStartTime(taskExecutionStartTime);
        criticalTask.setStatus(TaskStatus.RUNNING);
        try {
            criticalTask.execute();
            criticalTask.setStatus(TaskStatus.COMPLETED);
        } catch (Exception e) {
            LOGGER.severe(() -> String.format("[CRITICAL] Task [%s] failed: %s", criticalTask.getTaskId(), e.getMessage()));
        } finally {
            criticalTask.setCompletedTime(System.currentTimeMillis());
            executionEvents.add("CRITICAL_EXECUTED:" + criticalTask.getTaskId());
            LOGGER.info(() -> String.format("[CRITICAL] Critical task [%s] executed", criticalTask.getTaskId()));
        }

        // 3. After the critical task completes, pop the stack and resume the preempted task
        // from its saved context (status back to RUNNING, then COMPLETED)
        if (!preemptedTaskStack.isEmpty()) {
            ComputeTask resumedTask = preemptedTaskStack.pop();
            resumedTask.setStatus(TaskStatus.RUNNING);
            resumedTask.setResumedTime(System.currentTimeMillis());
            currentlyRunningTask = resumedTask;
            currentExecutionThread = Thread.currentThread();
            taskExecutionStartTime = System.currentTimeMillis();
            executionEvents.add("RESUMED:" + resumedTask.getTaskId());
            LOGGER.info(() -> String.format("[RESUME] Task [%s] resumed from saved context (remaining work: %d ms)",
                    resumedTask.getTaskId(), resumedTask.getRemainingDurationMillis()));
            try {
                resumedTask.execute();
                resumedTask.setStatus(TaskStatus.COMPLETED);
            } catch (Exception e) {
                LOGGER.severe(() -> String.format("[RESUME] Task [%s] failed: %s", resumedTask.getTaskId(), e.getMessage()));
            } finally {
                resumedTask.setCompletedTime(System.currentTimeMillis());
                executionEvents.add("COMPLETED:" + resumedTask.getTaskId());
                LOGGER.info(() -> String.format("[COMPLETED] Task [%s] resumed and completed", resumedTask.getTaskId()));
                currentlyRunningTask = null;
                currentExecutionThread = null;
            }
        } else {
            currentlyRunningTask = null;
            currentExecutionThread = null;
        }

        return criticalTask;
    }

    /**
     * Evaluates the top pending task against the current carbon intensity.
     * If eligible, removes the task from the queue and executes it.
     * If a CRITICAL task is submitted while another task is executing,
     * executeCritical() is triggered automatically rather than waiting in the normal queue.
     *
     * @param currentCarbonIntensity current grid carbon intensity in gCO2/kWh
     * @return the executed ComputeTask, or null if no task was executed
     */
    public ComputeTask tick(double currentCarbonIntensity) {
        ComputeTask candidate;
        synchronized (this) {
            candidate = pendingQueue.peek();
            if (candidate == null) {
                LOGGER.fine("[TICK] No pending tasks in queue.");
                return null;
            }

            LOGGER.info(() -> String.format("[TICK] Evaluating top task [%s] (Priority: %s, Threshold: %.1f gCO2/kWh) against current carbon intensity: %.1f gCO2/kWh",
                    candidate.getTaskId(), candidate.getPriority(), candidate.getCarbonThresholdGramsPerKwh(), currentCarbonIntensity));

            if (!candidate.isEligibleToRun(currentCarbonIntensity)) {
                LOGGER.info(() -> String.format("[DECISION] Task [%s] IS NOT ELIGIBLE to run (Threshold: %.1f gCO2/kWh < Current: %.1f gCO2/kWh). Retaining in queue for better conditions.",
                        candidate.getTaskId(), candidate.getCarbonThresholdGramsPerKwh(), currentCarbonIntensity));
                return null;
            }

            pendingQueue.poll();
            LOGGER.info(() -> String.format("[DECISION] Task [%s] IS ELIGIBLE to run. Starting execution...", candidate.getTaskId()));

            currentlyRunningTask = candidate;
            currentExecutionThread = Thread.currentThread();
            taskExecutionStartTime = System.currentTimeMillis();
            candidate.setStartTime(taskExecutionStartTime);
            candidate.setStatus(TaskStatus.RUNNING);
            criticalTaskToPreempt = null;
        }

        try {
            candidate.execute();
        } catch (Exception e) {
            LOGGER.severe(() -> String.format("[OUTCOME] Task [%s] failed with exception: %s", candidate.getTaskId(), e.getMessage()));
        }

        synchronized (this) {
            ComputeTask critTask = criticalTaskToPreempt;
            if (critTask == null && candidate.getStatus() == TaskStatus.PREEMPTED) {
                critTask = findCriticalTaskInQueue();
            }

            if (critTask != null) {
                criticalTaskToPreempt = null;
                Thread.interrupted(); // clear interrupt status
                executeCritical(critTask);
            } else {
                if (candidate.getStatus() == TaskStatus.RUNNING) {
                    candidate.setStatus(TaskStatus.COMPLETED);
                }
                candidate.setCompletedTime(System.currentTimeMillis());
                currentlyRunningTask = null;
                currentExecutionThread = null;
                LOGGER.info(() -> String.format("[OUTCOME] Task [%s] finished execution with status: %s", candidate.getTaskId(), candidate.getStatus()));
            }
        }

        return candidate;
    }

    private ComputeTask findCriticalTaskInQueue() {
        for (ComputeTask task : pendingQueue) {
            if (task.getPriority() == TaskPriority.CRITICAL) {
                return task;
            }
        }
        return null;
    }

    /**
     * Overloaded tick method that obtains current carbon intensity from an EnvironmentalDataProvider.
     *
     * @param provider EnvironmentalDataProvider
     * @return executed ComputeTask, or null
     * @throws EnvironmentalDataException if fetching intensity fails
     */
    public ComputeTask tick(EnvironmentalDataProvider provider) throws EnvironmentalDataException {
        if (provider == null) {
            throw new IllegalArgumentException("EnvironmentalDataProvider cannot be null");
        }
        double currentIntensity = provider.getCurrentIntensity().carbonIntensityGCO2kWh();
        return tick(currentIntensity);
    }

    /**
     * Returns a snapshot of the preempted tasks stack.
     *
     * @return Deque of preempted tasks
     */
    public synchronized Deque<ComputeTask> getPreemptedTaskStack() {
        return new ArrayDeque<>(preemptedTaskStack);
    }

    /**
     * Returns the number of currently preempted tasks waiting on the stack.
     *
     * @return number of preempted tasks
     */
    public synchronized int getPreemptedTaskCount() {
        return preemptedTaskStack.size();
    }

    /**
     * Returns the task currently being executed, or null if idle.
     *
     * @return currently running ComputeTask
     */
    public synchronized ComputeTask getCurrentlyRunningTask() {
        return currentlyRunningTask;
    }

    /**
     * Returns recorded scheduler execution lifecycle events.
     *
     * @return list of event descriptions
     */
    public List<String> getExecutionEvents() {
        return new ArrayList<>(executionEvents);
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
