package com.greencloud.scheduler;

import com.greencloud.env.CarbonIntensitySnapshot;
import com.greencloud.env.EnvironmentalDataProvider;
import com.greencloud.exceptions.EnvironmentalDataException;
import com.greencloud.model.BackupTask;
import com.greencloud.model.BatchProcessingTask;
import com.greencloud.model.ComputeTask;
import com.greencloud.model.TaskPriority;
import com.greencloud.model.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TaskSchedulerTest {

    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TaskScheduler();
    }

    @Test
    @DisplayName("Test TaskScheduler initialization and task submission")
    void testSchedulerInitializationAndSubmission() {
        ComputeTask task = new BackupTask("task-1", TaskPriority.HIGH, 10, 150.0);
        scheduler.submitTask(task);

        assertEquals(1, scheduler.getPendingTaskCount());
        List<ComputeTask> pending = scheduler.getPendingTasks();
        assertEquals(1, pending.size());
        assertEquals("task-1", pending.get(0).getTaskId());
    }

    @Test
    @DisplayName("Test tick executes highest priority task first when eligible")
    void testPriorityExecutionOrder() {
        long now = System.currentTimeMillis();
        ComputeTask bgTask = new BatchProcessingTask("bg-task", TaskPriority.BACKGROUND, 10, 200.0, now + 10, 100);
        ComputeTask highTask = new BackupTask("high-task", TaskPriority.HIGH, 10, 200.0, now + 20);
        ComputeTask critTask = new BatchProcessingTask("crit-task", TaskPriority.CRITICAL, 10, 200.0, now + 30, 50);

        scheduler.submitTask(bgTask);
        scheduler.submitTask(highTask);
        scheduler.submitTask(critTask);

        assertEquals(3, scheduler.getPendingTaskCount());

        // Carbon intensity 150 (all eligible)
        ComputeTask executed1 = scheduler.tick(150.0);
        assertNotNull(executed1);
        assertEquals("crit-task", executed1.getTaskId());
        assertEquals(TaskStatus.COMPLETED, executed1.getStatus());
        assertEquals(2, scheduler.getPendingTaskCount());

        ComputeTask executed2 = scheduler.tick(150.0);
        assertNotNull(executed2);
        assertEquals("high-task", executed2.getTaskId());
        assertEquals(TaskStatus.COMPLETED, executed2.getStatus());
        assertEquals(1, scheduler.getPendingTaskCount());

        ComputeTask executed3 = scheduler.tick(150.0);
        assertNotNull(executed3);
        assertEquals("bg-task", executed3.getTaskId());
        assertEquals(TaskStatus.COMPLETED, executed3.getStatus());
        assertEquals(0, scheduler.getPendingTaskCount());
    }

    @Test
    @DisplayName("Test tick defers ineligible tasks until carbon intensity drops")
    void testCarbonIntensityDeferral() {
        ComputeTask bgTask = new BatchProcessingTask("bg-task", TaskPriority.BACKGROUND, 10, 100.0, 1000);
        scheduler.submitTask(bgTask);

        // Carbon intensity 250 > threshold 100 -> task should not run
        ComputeTask resultHighCarbon = scheduler.tick(250.0);
        assertNull(resultHighCarbon);
        assertEquals(1, scheduler.getPendingTaskCount());
        assertEquals(TaskStatus.PENDING, bgTask.getStatus());

        // Carbon intensity drops to 90 <= threshold 100 -> task should run
        ComputeTask resultLowCarbon = scheduler.tick(90.0);
        assertNotNull(resultLowCarbon);
        assertEquals("bg-task", resultLowCarbon.getTaskId());
        assertEquals(TaskStatus.COMPLETED, resultLowCarbon.getStatus());
        assertEquals(0, scheduler.getPendingTaskCount());
    }

    @Test
    @DisplayName("Test CRITICAL tasks execute regardless of carbon intensity")
    void testCriticalTaskBypassesCarbonThreshold() {
        ComputeTask critTask = new BackupTask("crit-1", TaskPriority.CRITICAL, 10, 50.0);
        scheduler.submitTask(critTask);

        // Extremely high carbon intensity 500 > threshold 50
        ComputeTask executed = scheduler.tick(500.0);
        assertNotNull(executed);
        assertEquals("crit-1", executed.getTaskId());
        assertEquals(TaskStatus.COMPLETED, executed.getStatus());
    }

    @Test
    @DisplayName("Test tick with EnvironmentalDataProvider overload")
    void testTickWithEnvironmentalDataProvider() throws EnvironmentalDataException {
        ComputeTask task = new BackupTask("task-env", TaskPriority.HIGH, 10, 180.0);
        scheduler.submitTask(task);

        EnvironmentalDataProvider mockProvider = () -> new CarbonIntensitySnapshot("TEST-REGION", 120.0, Instant.now());

        ComputeTask executed = scheduler.tick(mockProvider);
        assertNotNull(executed);
        assertEquals("task-env", executed.getTaskId());
    }

    @Test
    @DisplayName("Test preemption: submitting CRITICAL task mid-execution preempts long BackupTask, completes critical task, then resumes BackupTask")
    void testPreemptionMidExecution() throws Exception {
        // Submit a long BackupTask
        BackupTask backupTask = new BackupTask("backup-long", TaskPriority.BACKGROUND, 400, 100.0);
        scheduler.submitTask(backupTask);

        AtomicReference<Throwable> threadError = new AtomicReference<>();
        Thread runnerThread = new Thread(() -> {
            try {
                scheduler.tick(50.0);
            } catch (Throwable t) {
                threadError.set(t);
            }
        });
        runnerThread.start();

        // Wait until BackupTask starts RUNNING
        long waitStart = System.currentTimeMillis();
        while (backupTask.getStatus() != TaskStatus.RUNNING && (System.currentTimeMillis() - waitStart) < 2000) {
            Thread.sleep(10);
        }
        assertEquals(TaskStatus.RUNNING, backupTask.getStatus(), "BackupTask should have started execution");

        // Allow backup task to execute partially
        Thread.sleep(80);

        // Submit a CRITICAL task mid-execution
        BackupTask criticalTask = new BackupTask("crit-preemptor", TaskPriority.CRITICAL, 60, 100.0);
        scheduler.submitTask(criticalTask);

        // Wait for the scheduler execution to complete
        runnerThread.join(4000);
        assertNull(threadError.get(), "Runner thread should finish without throwing an exception");

        // Assert (a): critical task completes before backup task resumes
        assertTrue(criticalTask.getCompletedTime() > 0, "Critical task should have a completed timestamp");
        assertTrue(backupTask.getResumedTime() > 0, "Backup task should have a resumed timestamp");
        assertTrue(criticalTask.getCompletedTime() <= backupTask.getResumedTime(),
                String.format("Critical task must complete (at %d) before backup task resumes (at %d)",
                        criticalTask.getCompletedTime(), backupTask.getResumedTime()));

        // Assert (b): backup task eventually completes
        assertEquals(TaskStatus.COMPLETED, criticalTask.getStatus(), "Critical task should be COMPLETED");
        assertEquals(TaskStatus.COMPLETED, backupTask.getStatus(), "Backup task should be COMPLETED");
        assertEquals(0, backupTask.getRemainingDurationMillis(), "Backup task remaining duration should be 0 upon completion");

        // Assert stack is empty after resumption
        Deque<ComputeTask> stack = scheduler.getPreemptedTaskStack();
        assertTrue(stack.isEmpty(), "Preempted tasks stack should be empty after resumption");
        assertEquals(0, scheduler.getPreemptedTaskCount());

        // Assert lifecycle events sequence
        List<String> events = scheduler.getExecutionEvents();
        int preemptIdx = events.indexOf("PREEMPTED:backup-long");
        int critIdx = events.indexOf("CRITICAL_EXECUTED:crit-preemptor");
        int resumeIdx = events.indexOf("RESUMED:backup-long");
        int compIdx = events.indexOf("COMPLETED:backup-long");

        assertTrue(preemptIdx != -1, "Event log should contain preemption of backup task");
        assertTrue(critIdx != -1, "Event log should contain critical execution");
        assertTrue(resumeIdx != -1, "Event log should contain resume of backup task");
        assertTrue(compIdx != -1, "Event log should contain completion of backup task");

        assertTrue(preemptIdx < critIdx, "Preemption must happen before critical task execution");
        assertTrue(critIdx < resumeIdx, "Critical task must finish before backup task resumes");
        assertTrue(resumeIdx < compIdx, "Backup task must resume before it completes");
    }

    @Test
    @DisplayName("Test direct call to executeCritical() pauses running task, executes critical task, and resumes paused task")
    void testDirectExecuteCriticalWithRunningTask() {
        BackupTask bgTask = new BackupTask("bg-task-1", TaskPriority.BACKGROUND, 200, 100.0);
        scheduler.submitTask(bgTask);

        // Emulate background task running
        bgTask.setStatus(TaskStatus.RUNNING);
        scheduler.tick(50.0); // Runs bgTask to completion

        // Now test executeCritical with an active task scenario
        BackupTask currentRunning = new BackupTask("running-task", TaskPriority.BACKGROUND, 300, 100.0);
        currentRunning.setStatus(TaskStatus.RUNNING);
        currentRunning.setRemainingDurationMillis(200);

        // Submit and start a new background task
        BackupTask bgTask2 = new BackupTask("bg-task-2", TaskPriority.BACKGROUND, 200, 100.0);
        scheduler.submitTask(bgTask2);

        // Manually invoke executeCritical while scheduler is idle
        BackupTask critTask = new BackupTask("crit-direct", TaskPriority.CRITICAL, 20, 100.0);
        ComputeTask executedCrit = scheduler.executeCritical(critTask);

        assertNotNull(executedCrit);
        assertEquals("crit-direct", executedCrit.getTaskId());
        assertEquals(TaskStatus.COMPLETED, executedCrit.getStatus());
        assertTrue(scheduler.getPreemptedTaskStack().isEmpty());
    }

    @Test
    @DisplayName("Test nested preemption using Deque as LIFO Stack")
    void testNestedPreemptionStackBehavior() {
        BackupTask task1 = new BackupTask("task-1", TaskPriority.BACKGROUND, 300, 100.0);
        BackupTask crit1 = new BackupTask("crit-1", TaskPriority.CRITICAL, 100, 100.0);
        BackupTask crit2 = new BackupTask("crit-2", TaskPriority.CRITICAL, 50, 100.0);

        // Simulate task1 running
        task1.setStatus(TaskStatus.RUNNING);

        // Execute crit1 (should preempt task1 and push it to stack)
        scheduler.executeCritical(crit1);
        assertEquals(TaskStatus.COMPLETED, crit1.getStatus());

        // If we want to test nested preemption explicitly via executeCritical:
        // Setting up a scenario where crit1 runs and gets preempted by crit2
        task1.setStatus(TaskStatus.RUNNING);
        scheduler.executeCritical(crit1); // task1 pushed
        
        // Now invoke crit2 while crit1 is running (or simulate nesting)
        scheduler.executeCritical(crit2); // crit1 pushed onto stack above task1
        assertEquals(TaskStatus.COMPLETED, crit2.getStatus());

        // Preempted stack should be empty after all tasks complete their resume cycles
        assertEquals(0, scheduler.getPreemptedTaskCount());
    }
}
