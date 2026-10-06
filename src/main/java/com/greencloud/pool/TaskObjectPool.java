package com.greencloud.pool;

import com.greencloud.model.BatchProcessingTask;
import com.greencloud.model.ComputeTask;
import com.greencloud.model.TaskPriority;
import com.greencloud.model.TaskStatus;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Thread-safe object pool managing a fixed-size pool of reusable ComputeTask instances.
 * <p>
 * Long-running schedulers frequently allocate and discard short-lived task objects.
 * By recycling completed tasks instead of continuously creating new ones, this pool
 * reduces Garbage Collection (GC) churn and allocation overhead.
 * </p>
 *
 * @param <T> concrete type of ComputeTask managed by the pool
 */
public class TaskObjectPool<T extends ComputeTask> {

    private static final Logger LOGGER = Logger.getLogger(TaskObjectPool.class.getName());

    private final Queue<T> pool;
    private final Supplier<T> factory;
    private final int maxSize;
    private final AtomicInteger poolSize;
    private final AtomicInteger totalCreated;

    /**
     * Constructs a TaskObjectPool with a custom task factory and maximum pool size.
     *
     * @param factory Supplier function used to instantiate new ComputeTask objects when the pool is empty
     * @param maxSize maximum number of idle task objects allowed in the pool
     */
    public TaskObjectPool(Supplier<T> factory, int maxSize) {
        if (factory == null) {
            throw new IllegalArgumentException("Factory supplier cannot be null");
        }
        if (maxSize <= 0) {
            throw new IllegalArgumentException("Max size must be greater than 0");
        }
        this.factory = factory;
        this.maxSize = maxSize;
        this.pool = new ConcurrentLinkedQueue<>();
        this.poolSize = new AtomicInteger(0);
        this.totalCreated = new AtomicInteger(0);
    }

    /**
     * Constructs a default TaskObjectPool managing BatchProcessingTask instances with a maximum pool size.
     *
     * @param maxSize maximum number of idle task objects allowed in the pool
     */
    @SuppressWarnings("unchecked")
    public TaskObjectPool(int maxSize) {
        this((Supplier<T>) createDefaultBatchTaskSupplier(), maxSize);
    }

    private static Supplier<BatchProcessingTask> createDefaultBatchTaskSupplier() {
        AtomicInteger counter = new AtomicInteger(0);
        return () -> new BatchProcessingTask(
                "pooled-batch-task-" + counter.incrementAndGet(),
                TaskPriority.BACKGROUND,
                1000,
                100.0,
                System.currentTimeMillis(),
                0
        );
    }

    /**
     * Acquires an available task object from the pool.
     * If the pool is empty, a new task object is created via the factory.
     * Resets the task state to PENDING before returning.
     *
     * @return an available or newly created ComputeTask instance with PENDING status
     */
    public T acquire() {
        T pooledTask = pool.poll();
        if (pooledTask != null) {
            poolSize.decrementAndGet();
            resetTaskState(pooledTask);
            LOGGER.fine(() -> String.format("[POOL ACQUIRE] Reused existing task object [%s]. Available in pool: %d",
                    pooledTask.getTaskId(), poolSize.get()));
            return pooledTask;
        }

        T newTask = factory.get();
        totalCreated.incrementAndGet();
        resetTaskState(newTask);
        LOGGER.fine(() -> String.format("[POOL ACQUIRE] Pool empty. Created new task object [%s]. Total created: %d",
                newTask.getTaskId(), totalCreated.get()));
        return newTask;
    }

    /**
     * Returns a completed or processed task object back to the pool.
     * Resets its mutable fields before storing. If the pool has reached its maximum size,
     * the task is ignored and allowed to be garbage collected.
     *
     * @param task ComputeTask instance to return to the pool
     */
    public void release(T task) {
        if (task == null) {
            return;
        }

        resetTaskState(task);

        while (true) {
            int currentSize = poolSize.get();
            if (currentSize >= maxSize) {
                LOGGER.fine(() -> String.format("[POOL RELEASE] Pool full (%d/%d). Discarding task [%s] for GC.",
                        currentSize, maxSize, task.getTaskId()));
                break;
            }
            if (poolSize.compareAndSet(currentSize, currentSize + 1)) {
                pool.offer(task);
                LOGGER.fine(() -> String.format("[POOL RELEASE] Returned task [%s] to pool. Pool size: %d",
                        task.getTaskId(), poolSize.get()));
                break;
            }
        }
    }

    /**
     * Resets the mutable fields of a ComputeTask object to prepare it for reuse.
     * Preserves identity (taskId) and task history/logs.
     *
     * @param task ComputeTask to reset
     */
    protected void resetTaskState(ComputeTask task) {
        if (task == null) return;
        task.setStatus(TaskStatus.PENDING);
        task.setSubmittedAt(System.currentTimeMillis());
        if (task instanceof BatchProcessingTask batchTask) {
            batchTask.setRecordCount(0);
        }
    }

    /**
     * Returns the current number of idle task objects available in the pool.
     *
     * @return current pool size
     */
    public int getPoolSize() {
        return poolSize.get();
    }

    /**
     * Returns the maximum capacity of idle task objects allowed in the pool.
     *
     * @return maximum pool size
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Returns the total number of task objects created by this pool.
     *
     * @return total created task count
     */
    public int getTotalCreatedCount() {
        return totalCreated.get();
    }

    /**
     * Clears all idle objects from the pool.
     */
    public void clear() {
        pool.clear();
        poolSize.set(0);
    }
}
