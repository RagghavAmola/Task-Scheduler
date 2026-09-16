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
import java.util.List;

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
}
