package com.greencloud.pool;

import com.greencloud.model.BatchProcessingTask;
import com.greencloud.model.TaskPriority;
import com.greencloud.model.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskObjectPoolTest {

    @Test
    @DisplayName("Verify that the pool reuses instances rather than always allocating new ones using object identity")
    void testPoolReuseInstanceIdentity() {
        TaskObjectPool<BatchProcessingTask> pool = new TaskObjectPool<>(5);

        // Acquire two task objects from the empty pool
        BatchProcessingTask task1 = pool.acquire();
        BatchProcessingTask task2 = pool.acquire();

        assertNotNull(task1);
        assertNotNull(task2);
        assertNotSame(task1, task2, "Initial acquisitions from an empty pool should return distinct instances");
        assertEquals(TaskStatus.PENDING, task1.getStatus());

        // Simulate execution of task1
        task1.execute();
        assertEquals(TaskStatus.COMPLETED, task1.getStatus());

        // Release task1 back to the pool
        pool.release(task1);

        // Acquire a task object again; it should reuse task1
        BatchProcessingTask task3 = pool.acquire();
        assertSame(task1, task3, "Pool should reuse the released instance (same object identity)");
        assertEquals(TaskStatus.PENDING, task3.getStatus(), "Acquired instance state must be reset to PENDING");
    }

    @Test
    @DisplayName("Verify max size limit for retained idle objects in the pool")
    void testPoolMaxSizeLimit() {
        int maxSize = 2;
        TaskObjectPool<BatchProcessingTask> pool = new TaskObjectPool<>(maxSize);

        // Acquire 4 objects
        BatchProcessingTask t1 = pool.acquire();
        BatchProcessingTask t2 = pool.acquire();
        BatchProcessingTask t3 = pool.acquire();
        BatchProcessingTask t4 = pool.acquire();

        // Release all 4 objects
        pool.release(t1);
        pool.release(t2);
        pool.release(t3);
        pool.release(t4);

        // Max pool size is 2, so poolSize should be capped at 2
        assertEquals(2, pool.getPoolSize(), "Pool size should not exceed maxSize");
    }

    @Test
    @DisplayName("Verify state reset on acquire and release")
    void testTaskStateReset() {
        AtomicInteger counter = new AtomicInteger(0);
        TaskObjectPool<BatchProcessingTask> pool = new TaskObjectPool<>(
                () -> new BatchProcessingTask("custom-task-" + counter.incrementAndGet(), TaskPriority.HIGH, 500, 150.0, 100),
                3
        );

        BatchProcessingTask task = pool.acquire();
        assertEquals(TaskStatus.PENDING, task.getStatus());
        task.setStatus(TaskStatus.RUNNING);
        task.setRecordCount(250);

        pool.release(task);

        BatchProcessingTask reusedTask = pool.acquire();
        assertSame(task, reusedTask);
        assertEquals(TaskStatus.PENDING, reusedTask.getStatus());
        assertEquals(0, reusedTask.getRecordCount(), "Record count should be reset to 0");
    }

    @Test
    @DisplayName("Verify thread safety under concurrent acquire and release operations")
    void testConcurrentAcquireAndRelease() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        int maxPoolSize = 5;

        TaskObjectPool<BatchProcessingTask> pool = new TaskObjectPool<>(maxPoolSize);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        BatchProcessingTask task = pool.acquire();
                        assertNotNull(task);
                        // Simulate minor workload
                        task.setStatus(TaskStatus.RUNNING);
                        pool.release(task);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(finished, "Concurrent operations completed within timeout");
        assertTrue(pool.getPoolSize() <= maxPoolSize, "Pool size should remain within bounds under concurrency");
    }
    @Test
    @DisplayName("Verify constructor validation and null release handling")
    void testEdgeCases() {
        assertThrows(IllegalArgumentException.class, () -> new TaskObjectPool<>(0));
        assertThrows(IllegalArgumentException.class, () -> new TaskObjectPool<>(null, 5));

        TaskObjectPool<BatchProcessingTask> pool = new TaskObjectPool<>(5);
        assertDoesNotThrow(() -> pool.release(null), "Releasing null should gracefully do nothing");
    }

    @Test
    @DisplayName("Verify clearing the pool removes all idle instances")
    void testClearPool() {
        TaskObjectPool<BatchProcessingTask> pool = new TaskObjectPool<>(5);
        BatchProcessingTask task = pool.acquire();
        pool.release(task);
        assertEquals(1, pool.getPoolSize());

        pool.clear();
        assertEquals(0, pool.getPoolSize());
    }
}
