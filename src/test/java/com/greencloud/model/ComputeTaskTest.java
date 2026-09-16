package com.greencloud.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.*;

class ComputeTaskTest {

    @Test
    @DisplayName("Test isEligibleToRun logic for CRITICAL vs carbon-sensitive tasks")
    void testIsEligibleToRun() {
        ComputeTask criticalTask = new BackupTask("task-1", TaskPriority.CRITICAL, 1000, 150.0);
        ComputeTask highTask = new BatchProcessingTask("task-2", TaskPriority.HIGH, 2000, 200.0);
        ComputeTask backgroundTask = new BackupTask("task-3", TaskPriority.BACKGROUND, 5000, 100.0);

        // CRITICAL tasks should always be eligible regardless of intensity
        assertTrue(criticalTask.isEligibleToRun(300.0));
        assertTrue(criticalTask.isEligibleToRun(100.0));

        // HIGH priority task threshold is 200.0
        assertTrue(highTask.isEligibleToRun(150.0));
        assertTrue(highTask.isEligibleToRun(200.0));
        assertFalse(highTask.isEligibleToRun(250.0));

        // BACKGROUND priority task threshold is 100.0
        assertTrue(backgroundTask.isEligibleToRun(90.0));
        assertFalse(backgroundTask.isEligibleToRun(101.0));
    }

    @Test
    @DisplayName("Test natural ordering in PriorityQueue by priority then by submittedAt")
    void testPriorityQueueOrdering() {
        long baseTime = System.currentTimeMillis();

        ComputeTask bgTask = new BatchProcessingTask("bg-1", TaskPriority.BACKGROUND, 1000, 100.0, baseTime + 10);
        ComputeTask highTask1 = new BackupTask("high-1", TaskPriority.HIGH, 1000, 100.0, baseTime + 20);
        ComputeTask highTask2 = new BackupTask("high-2", TaskPriority.HIGH, 1000, 100.0, baseTime + 5); // earlier high task
        ComputeTask criticalTask = new BatchProcessingTask("crit-1", TaskPriority.CRITICAL, 1000, 100.0, baseTime + 30);

        PriorityQueue<ComputeTask> queue = new PriorityQueue<>();
        queue.add(bgTask);
        queue.add(highTask1);
        queue.add(highTask2);
        queue.add(criticalTask);

        // Polling order should be: CRITICAL, then earlier HIGH, then later HIGH, then BACKGROUND
        assertEquals("crit-1", queue.poll().getTaskId());
        assertEquals("high-2", queue.poll().getTaskId());
        assertEquals("high-1", queue.poll().getTaskId());
        assertEquals("bg-1", queue.poll().getTaskId());
    }

    @Test
    @DisplayName("Test BackupTask execute() flow and status transitions")
    void testBackupTaskExecute() {
        BackupTask task = new BackupTask("bk-100", TaskPriority.HIGH, 50, 150.0, "/tmp/src", "/tmp/dst");
        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertEquals("/tmp/src", task.getSourcePath());
        assertEquals("/tmp/dst", task.getDestinationPath());

        task.execute();

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
    }

    @Test
    @DisplayName("Test BatchProcessingTask execute() flow and status transitions")
    void testBatchProcessingTaskExecute() {
        BatchProcessingTask task = new BatchProcessingTask("batch-200", TaskPriority.BACKGROUND, 50, 120.0, 500);
        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertEquals(500, task.getRecordCount());

        task.execute();

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
    }

    @Test
    @DisplayName("Test equals, hashCode, and toString")
    void testEqualsAndHashCode() {
        long submittedAt = 1700000000000L;
        ComputeTask task1 = new BackupTask("task-1", TaskPriority.HIGH, 1000, 150.0, submittedAt);
        ComputeTask task2 = new BackupTask("task-1", TaskPriority.HIGH, 1000, 150.0, submittedAt);
        ComputeTask task3 = new BatchProcessingTask("task-2", TaskPriority.HIGH, 1000, 150.0, submittedAt);

        assertEquals(task1, task2);
        assertEquals(task1.hashCode(), task2.hashCode());
        assertNotEquals(task1, task3);

        assertTrue(task1.toString().contains("taskId='task-1'"));
        assertTrue(task1.toString().contains("priority=HIGH"));
    }
}
