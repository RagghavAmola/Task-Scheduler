package com.greencloud.scheduler;

import com.greencloud.env.EnvironmentalDataProvider;
import com.greencloud.exceptions.EnvironmentalDataException;
import com.greencloud.exceptions.SchedulingException;
import com.greencloud.model.ComputeTask;
import com.greencloud.model.TaskPriority;
import com.greencloud.model.TaskStatus;
import com.greencloud.pool.TaskObjectPool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TaskScheduler manages submission and single-task execution of ComputeTasks,
 * prioritizing tasks based on priority level and submission timestamp while enforcing
 * carbon intensity thresholds. Supports preemption for CRITICAL priority tasks.
 * <p>
 * Hardened exception handling:
 * <ul>
 *   <li>Validates task submissions, throwing {@link SchedulingException} for invalid inputs.</li>
 *   <li>Catches task execution failures (e.g., RuntimeException), records status as {@link TaskStatus#FAILED},
 *       and continues scheduler operation without dropping remaining queued tasks.</li>
 *   <li>Falls back to last known carbon intensity if provider fails during tick execution.</li>
 * </ul>
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
    private final EnvironmentalDataProvider provider;
    private final TaskObjectPool<ComputeTask> objectPool;
    private final ReentrantLock schedulerLock = new ReentrantLock();
    private volatile Double lastKnownCarbonIntensity;

    public TaskScheduler(EnvironmentalDataProvider provider, TaskObjectPool<ComputeTask> objectPool) {
        this.provider = provider;
        this.objectPool = objectPool;
        this.pendingQueue = new PriorityQueue<>();
        this.preemptedTaskStack = new ArrayDeque<>();
        this.executionEvents = new CopyOnWriteArrayList<>();
    }

    // Overloaded single-argument constructor
    public TaskScheduler(EnvironmentalDataProvider provider) {
        this(provider, null);
    }

    // Default no-argument constructor (matches original behavior)
    public TaskScheduler() {
        this(null, null);
    }

    /**
     * Validates task fields for submission integrity.
     *
     * @param task ComputeTask to validate
     * @throws SchedulingException if any submission precondition is violated
     */
    private void validateTask(ComputeTask task) {
        if (task == null) {
            throw new SchedulingException("Task cannot be null");
        }
        if (task.getTaskId() == null || task.getTaskId().trim().isEmpty()) {
            throw new SchedulingException("Task ID cannot be null or empty");
        }
        if (task.getPriority() == null) {
            throw new SchedulingException("Task priority cannot be null for task: " + task.getTaskId());
        }
        if (task.getDurationMillis() < 0) {
            throw new SchedulingException(String.format("Task duration cannot be negative: %d ms (task: %s)",
                    task.getDurationMillis(), task.getTaskId()));
        }
        if (task.getRemainingDurationMillis() < 0) {
            throw new SchedulingException(String.format("Task remaining duration cannot be negative: %d ms (task: %s)",
                    task.getRemainingDurationMillis(), task.getTaskId()));
        }
        if (task.getCarbonThresholdGramsPerKwh() < 0) {
            throw new SchedulingException(String.format("Task carbon threshold cannot be negative: %.1f gCO2/kWh (task: %s)",
                    task.getCarbonThresholdGramsPerKwh(), task.getTaskId()));
        }
    }

    /**
     * Submits a compute task to the pending priority queue.
     * If a CRITICAL task is submitted while another task is currently executing,
     * triggers preemption of the running task so that the critical task can execute immediately.
     *
     * @param task ComputeTask to schedule
     * @throws SchedulingException if the task is null or has invalid attributes
     */
    public void submitTask(ComputeTask task) {
        validateTask(task);

        schedulerLock.lock();
        try {
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
        } finally {
            schedulerLock.unlock();
        }
    }

    /**
     * Executes a critical task with preemption semantics:
     * 1. If a background task is currently RUNNING, pause it: save its remaining work/duration,
     *    set status to PREEMPTED, and push it onto the stack.
     * 2. Immediately execute the critical task.
     * 3. After the critical task completes, pop the stack and resume the preempted task
     *    from its saved context (status back to RUNNING, then COMPLETED or FAILED).
     *
     * @param criticalTask the CRITICAL priority ComputeTask to execute immediately
     * @return the executed critical task
     * @throws SchedulingException if criticalTask is invalid or not of CRITICAL priority
     */
    public synchronized ComputeTask executeCritical(ComputeTask criticalTask) {
        validateTask(criticalTask);
        if (criticalTask.getPriority() != TaskPriority.CRITICAL) {
            throw new SchedulingException("Task must have CRITICAL priority to execute via executeCritical: " + criticalTask.getTaskId());
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
            if (!preemptedTaskStack.contains(runningTask) && runningTask.getStatus() == TaskStatus.PREEMPTED) {
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
        } catch (Throwable t) {
            criticalTask.setStatus(TaskStatus.FAILED);
            LOGGER.log(Level.SEVERE, String.format("[CRITICAL] Task [%s] failed with exception: %s", criticalTask.getTaskId(), t.getMessage()), t);
        } finally {
            criticalTask.setCompletedTime(System.currentTimeMillis());
            if (criticalTask.getStatus() == TaskStatus.FAILED) {
                executionEvents.add("CRITICAL_FAILED:" + criticalTask.getTaskId());
            } else {
                executionEvents.add("CRITICAL_EXECUTED:" + criticalTask.getTaskId());
            }
            LOGGER.info(() -> String.format("[CRITICAL] Critical task [%s] finished with status: %s", criticalTask.getTaskId(), criticalTask.getStatus()));
            if (criticalTask.getStatus() == TaskStatus.COMPLETED && objectPool != null) {
                objectPool.release(criticalTask);
            }
        }

        // 3. After the critical task completes, pop the stack and resume the preempted task
        // from its saved context (status back to RUNNING, then COMPLETED or FAILED)
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
            } catch (Throwable t) {
                resumedTask.setStatus(TaskStatus.FAILED);
                LOGGER.log(Level.SEVERE, String.format("[RESUME] Task [%s] failed with exception: %s", resumedTask.getTaskId(), t.getMessage()), t);
            } finally {
                resumedTask.setCompletedTime(System.currentTimeMillis());
                if (resumedTask.getStatus() == TaskStatus.FAILED) {
                    executionEvents.add("FAILED:" + resumedTask.getTaskId());
                } else {
                    executionEvents.add("COMPLETED:" + resumedTask.getTaskId());
                }
                LOGGER.info(() -> String.format("[COMPLETED] Task [%s] resumed and completed with status: %s", resumedTask.getTaskId(), resumedTask.getStatus()));
                currentlyRunningTask = null;
                currentExecutionThread = null;
                if (resumedTask.getStatus() == TaskStatus.COMPLETED && objectPool != null) {
                    objectPool.release(resumedTask);
                }
            }
        } else {
            currentlyRunningTask = null;
            currentExecutionThread = null;
        }

        return criticalTask;
    }

    /**
     * Executes a scheduling tick using the configured EnvironmentalDataProvider.
     *
     * @return executed ComputeTask, or null if no eligible task was executed
     * @throws EnvironmentalDataException if fetching carbon intensity fails and no fallback is available
     */
    public ComputeTask tick() throws EnvironmentalDataException {
        if (this.provider == null) {
            throw new IllegalStateException("EnvironmentalDataProvider has not been configured");
        }
        return tick(this.provider);
    }

    /**
     * Evaluates the top pending task against the current carbon intensity.
     * If eligible, removes the task from the queue and executes it.
     * If a task's execute() throws an exception, marks the task as FAILED,
     * logs the incident, and keeps the scheduler running for remaining tasks.
     *
     * @param currentCarbonIntensity current grid carbon intensity in gCO2/kWh
     * @return the executed ComputeTask, or null if no task was executed
     */
    public ComputeTask tick(double currentCarbonIntensity) {
        ComputeTask candidate;

        // --- PHASE 1: ACQUIRE TASK UNDER LOCK ---
        schedulerLock.lock();
        try {
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
        } finally {
            schedulerLock.unlock();
        }

        // --- PHASE 2: TASK EXECUTION OUTSIDE LOCK ---
        boolean taskFailed = false;
        try {
            candidate.execute();
        } catch (Throwable t) {
            taskFailed = true;
            candidate.setStatus(TaskStatus.FAILED);
            LOGGER.log(Level.SEVERE, String.format("[OUTCOME] Task [%s] failed with exception: %s", candidate.getTaskId(), t.getMessage()), t);
        }

        // --- PHASE 3: COMPLETION / PREEMPTION HANDLING UNDER LOCK ---
        schedulerLock.lock();
        try {
            ComputeTask critTask = criticalTaskToPreempt;
            if (critTask == null && candidate.getStatus() == TaskStatus.PREEMPTED) {
                critTask = findCriticalTaskInQueue();
            }

            if (critTask != null) {
                criticalTaskToPreempt = null;
                Thread.interrupted(); // clear interrupt status
                executeCritical(critTask);
            } else {
                if (taskFailed || candidate.getStatus() == TaskStatus.FAILED) {
                    candidate.setStatus(TaskStatus.FAILED);
                    executionEvents.add("FAILED:" + candidate.getTaskId());
                    LOGGER.info(() -> String.format("[OUTCOME] Task [%s] finished with status: FAILED (scheduler loop remains healthy)", candidate.getTaskId()));
                } else if (candidate.getStatus() == TaskStatus.RUNNING) {
                    candidate.setStatus(TaskStatus.COMPLETED);
                    LOGGER.info(() -> String.format("[OUTCOME] Task [%s] finished execution with status: COMPLETED", candidate.getTaskId()));
                }
                candidate.setCompletedTime(System.currentTimeMillis());
                currentlyRunningTask = null;
                currentExecutionThread = null;

                if (candidate.getStatus() == TaskStatus.COMPLETED && objectPool != null) {
                    objectPool.release(candidate);
                }
            }
        } finally {
            schedulerLock.unlock();
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
     * If fetching data fails and a previously observed intensity is cached, falls back to that value.
     *
     * @param provider EnvironmentalDataProvider
     * @return executed ComputeTask, or null
     * @throws EnvironmentalDataException if fetching intensity fails and no fallback is available
     */
    public ComputeTask tick(EnvironmentalDataProvider provider) throws EnvironmentalDataException {
        if (provider == null) {
            throw new IllegalArgumentException("EnvironmentalDataProvider cannot be null");
        }
        try {
            double currentIntensity = provider.getCurrentIntensity().carbonIntensityGCO2kWh();
            this.lastKnownCarbonIntensity = currentIntensity;
            return tick(currentIntensity);
        } catch (EnvironmentalDataException e) {
            LOGGER.warning(() -> "[SCHEDULER] EnvironmentalDataProvider failed: " + e.getMessage());
            if (lastKnownCarbonIntensity != null) {
                LOGGER.info(() -> String.format("[SCHEDULER FALLBACK] Using last known carbon intensity: %.1f gCO2/kWh", lastKnownCarbonIntensity));
                return tick(lastKnownCarbonIntensity);
            }
            throw e;
        }
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

    public EnvironmentalDataProvider getProvider() {
        return this.provider;
    }

    public Double getLastKnownCarbonIntensity() {
        return lastKnownCarbonIntensity;
    }

    public void setLastKnownCarbonIntensity(Double lastKnownCarbonIntensity) {
        this.lastKnownCarbonIntensity = lastKnownCarbonIntensity;
    }
}
