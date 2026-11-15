package com.greencloud.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.*;

class ComputeTaskTest {

    /**
     * DECISION & SPECIFICATION: Threshold Boundary Conditions
     * ---------------------------------------------------------
     * Decision: When carbonThreshold == currentIntensity (exact boundary match),
     * the task is ELIGIBLE to execute (isEligibleToRun evaluates to true).
     *
     * Rationale:
     * 1. Carbon Budget Model: A task's carbonThresholdGramsPerKwh denotes the maximum
     *    acceptable carbon emission upper ceiling. If the real-time grid intensity does
     *    not exceed this threshold (currentIntensity <= carbonThreshold), execution
     *    remains strictly within carbon compliance limits.
     * 2. Starvation Avoidance: Disqualifying tasks at exact equality (< instead of <=)
     *    would lead to artificial, unnecessary deferrals and queue stagnation when the
     *    grid operates precisely at budgeted emissions targets.
     * 3. Architectural Contract: Aligns with ComputeTask.isEligibleToRun:
     *    `currentCarbonIntensity <= this.carbonThresholdGramsPerKwh`.
     */
    @ParameterizedTest(name = "[{index}] Priority={0}, Intensity={1}, Threshold={2} -> Eligible={3}")
    @CsvSource({
            // TaskPriority, Intensity, Threshold, ExpectedEligibility
            "BACKGROUND,   99.0,      100.0,     true",     // Strictly below threshold -> eligible
            "BACKGROUND,  100.0,      100.0,     true",     // Exactly equal to threshold -> ELIGIBLE (boundary condition)
            "BACKGROUND,  100.001,    100.0,     false",    // Just above threshold -> ineligible (boundary condition)
            "BACKGROUND,  101.0,      100.0,     false",    // Strictly above threshold -> ineligible
            "BACKGROUND,    0.0,      100.0,     true",     // Zero grid emission -> eligible
            "HIGH,        199.9,      200.0,     true",     // Below threshold -> eligible
            "HIGH,        200.0,      200.0,     true",     // Exactly equal -> ELIGIBLE (boundary condition)
            "HIGH,        200.001,    200.0,     false",    // Just above -> ineligible (boundary condition)
            "HIGH,        300.0,      200.0,     false"     // Substantially above -> ineligible
    })
    @DisplayName("Boundary verification: carbonThreshold == currentIntensity is eligible (<= boundary)")
    void testThresholdBoundaryConditionsParameterized(String priorityStr, double intensity, double threshold, boolean expectedEligible) {
        TaskPriority priority = TaskPriority.valueOf(priorityStr);
        ComputeTask task = new BatchProcessingTask("boundary-task", priority, 500, threshold, 100);

        boolean actualEligible = task.isEligibleToRun(intensity);
        assertEquals(expectedEligible, actualEligible,
                String.format("Task with priority %s and threshold %.3f tested against intensity %.3f should be eligible=%b",
                        priority, threshold, intensity, expectedEligible));
    }

    @ParameterizedTest(name = "[{index}] CRITICAL priority tested against carbon intensity={0} gCO2/kWh")
    @ValueSource(doubles = {0.0, 50.0, 100.0, 100.001, 200.0, 500.0, 9999.0})
    @DisplayName("Boundary verification: CRITICAL priority tasks are always eligible regardless of carbon intensity")
    void testCriticalPriorityAlwaysEligibleParameterized(double carbonIntensity) {
        // Critical task with low carbon threshold (e.g. 100.0 gCO2/kWh)
        ComputeTask criticalTask = new BackupTask("crit-task", TaskPriority.CRITICAL, 500, 100.0);
        assertTrue(criticalTask.isEligibleToRun(carbonIntensity),
                String.format("CRITICAL priority must bypass carbon gating even when grid intensity is %.1f", carbonIntensity));
    }

    @Test
    @DisplayName("Verify pairwise natural ordering between all priority levels")
    void testPairwisePriorityComparisons() {
        long now = System.currentTimeMillis();
        ComputeTask crit = new BackupTask("c", TaskPriority.CRITICAL, 100, 100.0, now);
        ComputeTask high = new BackupTask("h", TaskPriority.HIGH, 100, 100.0, now);
        ComputeTask bg = new BackupTask("b", TaskPriority.BACKGROUND, 100, 100.0, now);

        assertTrue(crit.compareTo(high) < 0, "CRITICAL should precede HIGH in priority ordering");
        assertTrue(high.compareTo(bg) < 0, "HIGH should precede BACKGROUND in priority ordering");
        assertTrue(crit.compareTo(bg) < 0, "CRITICAL should precede BACKGROUND in priority ordering");

        assertTrue(high.compareTo(crit) > 0, "HIGH should follow CRITICAL in priority ordering");
        assertTrue(bg.compareTo(high) > 0, "BACKGROUND should follow HIGH in priority ordering");
    }

    @Test
    @DisplayName("Verify FIFO ordering for equal priority tasks strictly follows submittedAt")
    void testEqualPriorityFifoComparison() {
        ComputeTask earlier = new BackupTask("t-early", TaskPriority.HIGH, 100, 100.0, 1000L);
        ComputeTask later = new BackupTask("t-late", TaskPriority.HIGH, 100, 100.0, 2000L);
        ComputeTask identicalTimeOtherId = new BackupTask("t-a", TaskPriority.HIGH, 100, 100.0, 1000L);

        assertTrue(earlier.compareTo(later) < 0, "Earlier task should precede later task");
        assertTrue(later.compareTo(earlier) > 0, "Later task should follow earlier task");
        // For identical priority and submittedAt, tie breaker falls back to taskId
        assertTrue(earlier.compareTo(identicalTimeOtherId) > 0, "Tie breaker orders by taskId ('t-early' vs 't-a')");
    }

    @Test
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
    void testBatchProcessingTaskPreemptionAndRemainingWork() throws InterruptedException {
        // Create a batch processing task with a 400ms duration and 100 records
        BatchProcessingTask task = new BatchProcessingTask("TASK-001",TaskPriority.HIGH,400L,50.0,100);

        assertEquals(400L, task.getRemainingDurationMillis());
        assertEquals(TaskStatus.PENDING, task.getStatus());

        // Run the task asynchronously in a separate thread so we can interrupt it
        Thread workerThread = new Thread(task::execute);
        workerThread.start();

        while (task.getStatus() != TaskStatus.RUNNING) {
            Thread.sleep(10L);
        }

        // Now let it run a bit inside the chunk before interrupting
        Thread.sleep(50L);
        workerThread.interrupt();
        workerThread.join();
        // Verify that the task status is PREEMPTED
        assertEquals(TaskStatus.PREEMPTED, task.getStatus());

        // Verify that remaining work is less than 400ms but greater than 0
        long remaining = task.getRemainingDurationMillis();
        assertTrue(remaining > 0 && remaining < 400L, 
                () -> "Remaining work should be partially completed, but was: " + remaining);

        // Resume the task to verify it completes using only its remaining time across chunks
        while (task.getRemainingDurationMillis() > 0 && task.getStatus() != TaskStatus.COMPLETED) {
            Thread resumeThread = new Thread(task::execute);
            resumeThread.start();
            resumeThread.join();
        }

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals(0L, task.getRemainingDurationMillis());
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

    @Test
    @DisplayName("Test remaining work, duration tracking, and completion percentage")
    void testRemainingWorkAndCompletionPercentage() {
        BackupTask task = new BackupTask("task-progress", TaskPriority.HIGH, 1000, 150.0);
        assertEquals(1000, task.getDurationMillis());
        assertEquals(1000, task.getRemainingDurationMillis());
        assertEquals(1000, task.getRemainingWork());
        assertEquals(0.0, task.getCompletionPercentage(), 0.01);

        task.setRemainingDurationMillis(750);
        assertEquals(750, task.getRemainingDurationMillis());
        assertEquals(750, task.getRemainingWork());
        assertEquals(25.0, task.getCompletionPercentage(), 0.01);

        task.setRemainingWork(500);
        assertEquals(500, task.getRemainingDurationMillis());
        assertEquals(500, task.getRemainingWork());
        assertEquals(50.0, task.getCompletionPercentage(), 0.01);

        task.setRemainingDurationMillis(0);
        assertEquals(100.0, task.getCompletionPercentage(), 0.01);
    }
}
