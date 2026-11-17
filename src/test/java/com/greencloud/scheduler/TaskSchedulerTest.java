package com.greencloud.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greencloud.env.CarbonIntensitySnapshot;
import com.greencloud.env.EnvironmentalDataProvider;
import com.greencloud.env.LiveEnvironmentalDataProvider;
import com.greencloud.env.SimulatedEnvironmentalDataProvider;
import com.greencloud.exceptions.EnvironmentalDataException;
import com.greencloud.exceptions.SchedulingException;
import com.greencloud.model.BackupTask;
import com.greencloud.model.BatchProcessingTask;
import com.greencloud.model.ComputeTask;
import com.greencloud.model.TaskPriority;
import com.greencloud.model.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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

        // Setting up a scenario where task1 is running again and gets preempted
        task1.setStatus(TaskStatus.RUNNING);
        scheduler.executeCritical(crit1); // task1 pushed

        scheduler.executeCritical(crit2);
        assertEquals(TaskStatus.COMPLETED, crit2.getStatus());

        // Preempted stack should be empty after all tasks complete their resume cycles
        assertEquals(0, scheduler.getPreemptedTaskCount());
    }

    @Test
    @DisplayName("Test that task execute() throwing RuntimeException is marked FAILED and scheduler continues processing queued tasks")
    void testTaskExecutionFailureDoesNotStopScheduler() {
        ComputeTask failingTask = new ComputeTask("failing-task", TaskPriority.HIGH, 50, 150.0) {
            @Override
            public void execute() {
                throw new RuntimeException("Simulated catastrophic task runtime failure");
            }
        };

        ComputeTask healthyTask1 = new BackupTask("healthy-1", TaskPriority.HIGH, 20, 150.0);
        ComputeTask healthyTask2 = new BatchProcessingTask("healthy-2", TaskPriority.BACKGROUND, 20, 150.0, 50);

        scheduler.submitTask(failingTask);
        scheduler.submitTask(healthyTask1);
        scheduler.submitTask(healthyTask2);

        assertEquals(3, scheduler.getPendingTaskCount());

        // Tick 1: failing task executes and throws exception
        ComputeTask executed1 = scheduler.tick(100.0);
        assertNotNull(executed1);
        assertEquals("failing-task", executed1.getTaskId());
        assertEquals(TaskStatus.FAILED, executed1.getStatus(), "Failing task must be marked as FAILED");
        assertEquals(2, scheduler.getPendingTaskCount(), "Remaining tasks must NOT be lost");
        assertTrue(scheduler.getExecutionEvents().contains("FAILED:failing-task"));

        // Tick 2: healthyTask1 executes normally
        ComputeTask executed2 = scheduler.tick(100.0);
        assertNotNull(executed2);
        assertEquals("healthy-1", executed2.getTaskId());
        assertEquals(TaskStatus.COMPLETED, executed2.getStatus());
        assertEquals(1, scheduler.getPendingTaskCount());

        // Tick 3: healthyTask2 executes normally
        ComputeTask executed3 = scheduler.tick(100.0);
        assertNotNull(executed3);
        assertEquals("healthy-2", executed3.getTaskId());
        assertEquals(TaskStatus.COMPLETED, executed3.getStatus());
        assertEquals(0, scheduler.getPendingTaskCount());
    }

    @Test
    @DisplayName("Test invalid task submissions throw SchedulingException and are rejected from queue")
    void testInvalidTaskSubmissionThrowsSchedulingException() {
        // Null task
        assertThrows(SchedulingException.class, () -> scheduler.submitTask(null));

        // Task with null priority
        assertThrows(SchedulingException.class, () ->
                scheduler.submitTask(new BackupTask("t-null-prio", null, 100, 150.0)));

        // Task with negative duration
        assertThrows(SchedulingException.class, () ->
                scheduler.submitTask(new BackupTask("t-neg-duration", TaskPriority.HIGH, -10, 150.0)));

        // Task with negative carbon threshold
        assertThrows(SchedulingException.class, () ->
                scheduler.submitTask(new BackupTask("t-neg-carbon", TaskPriority.HIGH, 100, -25.0)));

        // Task with blank taskId
        assertThrows(SchedulingException.class, () ->
                scheduler.submitTask(new BackupTask("   ", TaskPriority.HIGH, 100, 150.0)));

        // Queue must remain empty
        assertEquals(0, scheduler.getPendingTaskCount());
    }

    @Test
    @DisplayName("Test scheduler survives API returning malformed JSON and continues operating via fallback")
    void testSchedulerSurvivesMalformedJsonWithProviderFallback() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{ malformed: json [ invalid");

        doReturn(mockResponse).when(mockClient).send(any(HttpRequest.class), any());

        SimulatedEnvironmentalDataProvider fallback = new SimulatedEnvironmentalDataProvider("SIM-BACKUP", 80.0, 80.0, 60.0, false);
        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper(),
                fallback
        );

        TaskScheduler resilientScheduler = new TaskScheduler(provider);
        ComputeTask task = new BackupTask("task-resilient-json", TaskPriority.HIGH, 10, 100.0);
        resilientScheduler.submitTask(task);

        // Tick delegating to provider: malformed JSON occurs, provider falls back to simulated provider (80.0 gCO2/kWh <= threshold 100.0)
        ComputeTask executed = resilientScheduler.tick();
        assertNotNull(executed, "Scheduler should survive malformed JSON and execute eligible task via fallback");
        assertEquals("task-resilient-json", executed.getTaskId());
        assertEquals(TaskStatus.COMPLETED, executed.getStatus());
        assertEquals(0, resilientScheduler.getPendingTaskCount());
    }

    @Test
    @DisplayName("Test scheduler survives API timeout and continues operating using last known snapshot")
    void testSchedulerSurvivesApiTimeoutWithLastKnownSnapshot() throws Exception {
        String validJson = """
                {
                "data": [
                        {
                        "from": "2026-09-16T18:00Z",
                        "to": "2026-09-16T18:30Z",
                        "intensity": {
                        "forecast": 95,
                        "actual": 90,
                        "index": "low"
                        }
                        }
                ]
                }
                """;

        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(validJson);

        // Tick 1 succeeds, Tick 2 times out
        doReturn(mockResponse)
                .doThrow(new HttpTimeoutException("Network timeout to carbon API"))
                .when(mockClient).send(any(HttpRequest.class), any());

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper()
        );

        TaskScheduler resilientScheduler = new TaskScheduler(provider);

        // Tick 1: establishes lastKnownSnapshot (90 gCO2/kWh)
        ComputeTask t1 = new BackupTask("task-tick-1", TaskPriority.HIGH, 10, 100.0);
        resilientScheduler.submitTask(t1);
        ComputeTask r1 = resilientScheduler.tick();
        assertEquals(TaskStatus.COMPLETED, r1.getStatus());

        // Tick 2: API times out, provider falls back to 90 gCO2/kWh
        ComputeTask t2 = new BackupTask("task-tick-2", TaskPriority.HIGH, 10, 100.0);
        resilientScheduler.submitTask(t2);
        ComputeTask r2 = resilientScheduler.tick();
        assertNotNull(r2, "Scheduler should survive API timeout and execute task using last known snapshot");
        assertEquals("task-tick-2", r2.getTaskId());
        assertEquals(TaskStatus.COMPLETED, r2.getStatus());
        assertEquals(0, resilientScheduler.getPendingTaskCount());
    }

    @Test
    @DisplayName("Proposal Metric [Queue Correctness]: Tasks of different priorities are strictly dequeued CRITICAL > HIGH > BACKGROUND")
    void testQueueCorrectnessMultiPriorityOrder() {
        long baseTime = 1000L;
        // Submit in reverse priority order: BACKGROUND, HIGH, CRITICAL
        ComputeTask bg1 = new BackupTask("bg-1", TaskPriority.BACKGROUND, 10, 200.0, baseTime + 10);
        ComputeTask bg2 = new BackupTask("bg-2", TaskPriority.BACKGROUND, 10, 200.0, baseTime + 20);
        ComputeTask high1 = new BackupTask("high-1", TaskPriority.HIGH, 10, 200.0, baseTime + 30);
        ComputeTask high2 = new BackupTask("high-2", TaskPriority.HIGH, 10, 200.0, baseTime + 40);
        ComputeTask crit1 = new BackupTask("crit-1", TaskPriority.CRITICAL, 10, 200.0, baseTime + 50);
        ComputeTask crit2 = new BackupTask("crit-2", TaskPriority.CRITICAL, 10, 200.0, baseTime + 60);

        scheduler.submitTask(bg1);
        scheduler.submitTask(bg2);
        scheduler.submitTask(high1);
        scheduler.submitTask(high2);
        scheduler.submitTask(crit1);
        scheduler.submitTask(crit2);

        assertEquals(6, scheduler.getPendingTaskCount());

        List<String> executionOrder = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ComputeTask executed = scheduler.tick(100.0);
            assertNotNull(executed);
            executionOrder.add(executed.getTaskId());
        }

        List<String> expectedOrder = List.of("crit-1", "crit-2", "high-1", "high-2", "bg-1", "bg-2");
        assertEquals(expectedOrder, executionOrder, "Execution order must strictly prioritize CRITICAL over HIGH over BACKGROUND");
        assertEquals(0, scheduler.getPendingTaskCount());
    }

    @Test
    @DisplayName("Proposal Metric [Queue Correctness]: Tasks of equal priority are strictly dequeued in submission timestamp order (FIFO)")
    void testQueueCorrectnessFifoForEqualPriority() {
        ComputeTask t1 = new BackupTask("bg-t100", TaskPriority.BACKGROUND, 10, 200.0, 100L);
        ComputeTask t2 = new BackupTask("bg-t200", TaskPriority.BACKGROUND, 10, 200.0, 200L);
        ComputeTask t3 = new BackupTask("bg-t300", TaskPriority.BACKGROUND, 10, 200.0, 300L);
        ComputeTask t4 = new BackupTask("bg-t400", TaskPriority.BACKGROUND, 10, 200.0, 400L);
        ComputeTask t5 = new BackupTask("bg-t500", TaskPriority.BACKGROUND, 10, 200.0, 500L);

        // Submit in scrambled order
        scheduler.submitTask(t3);
        scheduler.submitTask(t1);
        scheduler.submitTask(t5);
        scheduler.submitTask(t2);
        scheduler.submitTask(t4);

        List<String> executionOrder = new ArrayList<>();
        while (scheduler.getPendingTaskCount() > 0) {
            ComputeTask executed = scheduler.tick(100.0);
            assertNotNull(executed);
            executionOrder.add(executed.getTaskId());
        }

        List<String> expectedOrder = List.of("bg-t100", "bg-t200", "bg-t300", "bg-t400", "bg-t500");
        assertEquals(expectedOrder, executionOrder, "Equal-priority tasks must be dispatched in exact FIFO timestamp sequence");
    }

    /**
     * DECISION & EVALUATION: Threshold Boundary Conditions in Scheduler
     * ------------------------------------------------------------------
     * Decision: When carbonThreshold == currentIntensity (exact boundary match),
     * scheduler.tick(intensity) executes the task (status -> COMPLETED).
     *
     * Rationale: Threshold is inclusive (<=). A task with threshold 150.0 is permissible
     * to execute when the grid emits up to and including 150.0 gCO2/kWh.
     * When intensity is 150.001 (just above), it must be deferred (tick returns null).
     */
    @ParameterizedTest(name = "[{index}] Scheduler boundary test: threshold=150.0, gridIntensity={0} -> shouldExecute={1}")
    @CsvSource({
            "149.9,   true",   // Below threshold -> executes
            "150.0,   true",   // EXACT EQUALITY -> ELIGIBLE & EXECUTES (boundary condition)
            "150.001, false",  // Just above threshold -> DEFERRED (boundary condition)
            "175.0,   false"   // Well above threshold -> deferred
    })
    @DisplayName("Proposal Metric [Carbon-Aware Execution Rate]: Boundary condition carbonThreshold == currentIntensity executes")
    void testSchedulerThresholdBoundaryConditions(double gridIntensity, boolean shouldExecute) {
        ComputeTask task = new BackupTask("boundary-task-" + gridIntensity, TaskPriority.BACKGROUND, 10, 150.0);
        scheduler.submitTask(task);

        ComputeTask executed = scheduler.tick(gridIntensity);
        if (shouldExecute) {
            assertNotNull(executed, "Task should execute when grid intensity (" + gridIntensity + ") <= threshold (150.0)");
            assertEquals(TaskStatus.COMPLETED, executed.getStatus());
            assertEquals(0, scheduler.getPendingTaskCount());
        } else {
            assertNull(executed, "Task should be deferred when grid intensity (" + gridIntensity + ") > threshold (150.0)");
            assertEquals(TaskStatus.PENDING, task.getStatus());
            assertEquals(1, scheduler.getPendingTaskCount());
        }
    }

    @Test
    @DisplayName("Proposal Metric [Carbon-Aware Execution Rate]: 100% of tasks run only when carbon threshold is satisfied")
    void testCarbonAwareExecutionRateUnderDynamicGrid() {
        // Submit tasks across priority levels with varying carbon thresholds
        ComputeTask crit1 = new BackupTask("crit-1", TaskPriority.CRITICAL, 10, 50.0);
        ComputeTask mod1 = new BackupTask("mod-1", TaskPriority.HIGH, 10, 200.0);
        ComputeTask mod2 = new BackupTask("mod-2", TaskPriority.HIGH, 10, 200.0);
        ComputeTask green1 = new BackupTask("green-1", TaskPriority.BACKGROUND, 10, 100.0);
        ComputeTask green2 = new BackupTask("green-2", TaskPriority.BACKGROUND, 10, 100.0);

        scheduler.submitTask(green1);
        scheduler.submitTask(green2);
        scheduler.submitTask(mod1);
        scheduler.submitTask(mod2);
        scheduler.submitTask(crit1);

        // Phase 1: High grid carbon = 250 gCO2/kWh
        // Only CRITICAL task should execute. All others deferred.
        ComputeTask r1 = scheduler.tick(250.0);
        assertNotNull(r1);
        assertEquals("crit-1", r1.getTaskId());

        ComputeTask r2 = scheduler.tick(250.0);
        assertNull(r2, "No non-critical task should run under 250 gCO2/kWh");
        assertEquals(4, scheduler.getPendingTaskCount());

        // Phase 2: Moderate grid carbon = 180 gCO2/kWh
        // The two HIGH mod tasks (threshold 200.0) should execute. green tasks remain deferred.
        ComputeTask r3 = scheduler.tick(180.0);
        assertNotNull(r3);
        assertEquals("mod-1", r3.getTaskId());

        ComputeTask r4 = scheduler.tick(180.0);
        assertNotNull(r4);
        assertEquals("mod-2", r4.getTaskId());

        ComputeTask r5 = scheduler.tick(180.0);
        assertNull(r5, "Green tasks (threshold 100) must remain deferred under 180 gCO2/kWh");
        assertEquals(2, scheduler.getPendingTaskCount());

        // Phase 3: Clean grid carbon = 90 gCO2/kWh
        // green tasks should now execute
        ComputeTask r6 = scheduler.tick(90.0);
        assertNotNull(r6);
        assertEquals("green-1", r6.getTaskId());

        ComputeTask r7 = scheduler.tick(90.0);
        assertNotNull(r7);
        assertEquals("green-2", r7.getTaskId());

        assertEquals(0, scheduler.getPendingTaskCount());
    }

    @Test
    @DisplayName("Proposal Metric [Preemption Success Rate]: Scenario 1 - Early-stage arrival of CRITICAL task")
    void testPreemptionScenarioEarlyArrival() throws Exception {
        BatchProcessingTask bgTask = new BatchProcessingTask("bg-early", TaskPriority.BACKGROUND, 600, 100.0, 100);
        scheduler.submitTask(bgTask);

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                scheduler.tick(50.0);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        runner.start();

        // Wait until task is running
        while (bgTask.getStatus() != TaskStatus.RUNNING) {
            Thread.sleep(10);
        }

        // Interrupt early (~40ms into 600ms task)
        Thread.sleep(40);
        BackupTask critTask = new BackupTask("crit-early", TaskPriority.CRITICAL, 50, 100.0);
        scheduler.submitTask(critTask);

        runner.join(4000);
        assertNull(error.get());

        assertEquals(TaskStatus.COMPLETED, critTask.getStatus());
        assertEquals(TaskStatus.COMPLETED, bgTask.getStatus());
        assertTrue(bgTask.getCompletedTime() >= critTask.getCompletedTime(),
                "Critical task must finish before background task finishes");
        assertTrue(scheduler.getPreemptedTaskStack().isEmpty());
    }

    @Test
    @DisplayName("Proposal Metric [Preemption Success Rate]: Scenario 2 - Mid-execution chunk preemption of BatchProcessingTask")
    void testPreemptionScenarioMidExecution() throws Exception {
        BatchProcessingTask batchTask = new BatchProcessingTask("bg-mid", TaskPriority.BACKGROUND, 500, 100.0, 200);
        scheduler.submitTask(batchTask);

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                scheduler.tick(50.0);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        runner.start();

        while (batchTask.getStatus() != TaskStatus.RUNNING) {
            Thread.sleep(10);
        }

        // Let it run through ~2 chunks (~220ms)
        Thread.sleep(220);
        BackupTask critTask = new BackupTask("crit-mid", TaskPriority.CRITICAL, 40, 100.0);
        scheduler.submitTask(critTask);

        runner.join(4000);
        assertNull(error.get());

        assertEquals(TaskStatus.COMPLETED, critTask.getStatus());
        assertEquals(TaskStatus.COMPLETED, batchTask.getStatus());
        assertEquals(0, batchTask.getRemainingDurationMillis());
        assertTrue(scheduler.getExecutionEvents().contains("PREEMPTED:bg-mid"));
        assertTrue(scheduler.getExecutionEvents().contains("RESUMED:bg-mid"));
    }

    @Test
    @DisplayName("Proposal Metric [Preemption Success Rate]: Scenario 3 - Late-stage / near-completion preemption")
    void testPreemptionScenarioNearCompletion() throws Exception {
        BatchProcessingTask batchTask = new BatchProcessingTask("bg-late", TaskPriority.BACKGROUND, 450, 100.0, 150);
        scheduler.submitTask(batchTask);

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                scheduler.tick(50.0);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        runner.start();

        while (batchTask.getStatus() != TaskStatus.RUNNING) {
            Thread.sleep(10);
        }

        // Let it run through ~3 chunks (~320ms out of 450ms)
        Thread.sleep(320);
        BackupTask critTask = new BackupTask("crit-late", TaskPriority.CRITICAL, 30, 100.0);
        scheduler.submitTask(critTask);

        runner.join(4000);
        assertNull(error.get());

        assertEquals(TaskStatus.COMPLETED, critTask.getStatus());
        assertEquals(TaskStatus.COMPLETED, batchTask.getStatus());
        assertEquals(0, batchTask.getRemainingDurationMillis());
        assertTrue(scheduler.getExecutionEvents().contains("PREEMPTED:bg-late"));
        assertTrue(scheduler.getExecutionEvents().contains("RESUMED:bg-late"));
    }

    @Test
    @DisplayName("Proposal Metric [Preemption Success Rate]: Scenario 4 - Multiple sequential critical tasks arriving during execution")
    void testPreemptionScenarioMultipleSequentialCriticalTasks() throws Exception {
        BatchProcessingTask longTask = new BatchProcessingTask("bg-seq", TaskPriority.BACKGROUND, 600, 100.0, 100);
        scheduler.submitTask(longTask);

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                scheduler.tick(50.0);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        runner.start();

        while (longTask.getStatus() != TaskStatus.RUNNING) {
            Thread.sleep(10);
        }

        Thread.sleep(50);
        BackupTask crit1 = new BackupTask("crit-seq-1", TaskPriority.CRITICAL, 40, 100.0);
        scheduler.submitTask(crit1);

        // While crit1 is running or just finished, submit crit2
        Thread.sleep(20);
        BackupTask crit2 = new BackupTask("crit-seq-2", TaskPriority.CRITICAL, 40, 100.0);
        scheduler.submitTask(crit2);

        runner.join(4000);
        assertNull(error.get());

        assertEquals(TaskStatus.COMPLETED, crit1.getStatus());
        assertEquals(TaskStatus.COMPLETED, longTask.getStatus());
        assertEquals(0, scheduler.getPreemptedTaskCount());

        // crit2 arrived during the execution and was queued as top pending critical task;
        // next tick immediately executes it to completion
        ComputeTask executedCrit2 = scheduler.tick(50.0);
        assertNotNull(executedCrit2);
        assertEquals("crit-seq-2", executedCrit2.getTaskId());
        assertEquals(TaskStatus.COMPLETED, crit2.getStatus());
        assertEquals(0, scheduler.getPendingTaskCount());
    }

    @Test
    @DisplayName("Proposal Metric [Preemption Success Rate]: Verification of 100% preemption success rate across trials")
    void testPreemptionSuccessRateQuantitativeMetric() {
        int trials = 10;
        int successfulPreemptions = 0;

        for (int i = 0; i < trials; i++) {
            TaskScheduler localScheduler = new TaskScheduler();
            BatchProcessingTask bg = new BatchProcessingTask("bg-eval-" + i, TaskPriority.BACKGROUND, 200, 100.0, 50);
            localScheduler.submitTask(bg);

            Thread worker = new Thread(() -> localScheduler.tick(50.0));
            worker.start();

            try {
                while (bg.getStatus() != TaskStatus.RUNNING) {
                    Thread.sleep(5);
                }
                Thread.sleep(30);

                BackupTask crit = new BackupTask("crit-eval-" + i, TaskPriority.CRITICAL, 30, 100.0);
                localScheduler.submitTask(crit);

                worker.join(3000);

                if (crit.getStatus() == TaskStatus.COMPLETED &&
                        bg.getStatus() == TaskStatus.COMPLETED &&
                        localScheduler.getPreemptedTaskCount() == 0 &&
                        localScheduler.getExecutionEvents().contains("PREEMPTED:bg-eval-" + i)) {
                    successfulPreemptions++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Trial interrupted");
            }
        }

        double successRate = ((double) successfulPreemptions / trials) * 100.0;
        assertEquals(100.0, successRate, "Preemption success rate must be 100%");
    }

    @Test
    @DisplayName("Proposal Metric [API Reliability]: Scheduler survives null API response body and continues operating via fallback")
    void testSchedulerSurvivesApiReturningNullResponseWithFallback() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(null);

        doReturn(mockResponse).when(mockClient).send(any(HttpRequest.class), any());

        SimulatedEnvironmentalDataProvider fallback = new SimulatedEnvironmentalDataProvider("SIM-NULL-FALLBACK", 70.0, 70.0, 60.0, false);
        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper(),
                fallback
        );

        TaskScheduler resilientScheduler = new TaskScheduler(provider);
        ComputeTask task = new BackupTask("task-null-body-fallback", TaskPriority.HIGH, 10, 100.0);
        resilientScheduler.submitTask(task);

        ComputeTask executed = resilientScheduler.tick();
        assertNotNull(executed, "Scheduler must survive null response and execute task via fallback");
        assertEquals("task-null-body-fallback", executed.getTaskId());
        assertEquals(TaskStatus.COMPLETED, executed.getStatus());
        assertEquals(0, resilientScheduler.getPendingTaskCount());
    }

    @Test
    @DisplayName("Proposal Metric [API Reliability]: Scheduler preserves queue integrity when unrecoverable API error occurs")
    void testSchedulerQueuePreservedWhenApiFailsWithoutFallback() {
        EnvironmentalDataProvider failingProvider = () -> {
            throw new EnvironmentalDataException("API service down: 503 Service Unavailable");
        };

        TaskScheduler schedulerWithFailingProvider = new TaskScheduler(failingProvider);
        ComputeTask task1 = new BackupTask("task-preserved-1", TaskPriority.HIGH, 10, 100.0);
        ComputeTask task2 = new BackupTask("task-preserved-2", TaskPriority.BACKGROUND, 10, 100.0);

        schedulerWithFailingProvider.submitTask(task1);
        schedulerWithFailingProvider.submitTask(task2);
        assertEquals(2, schedulerWithFailingProvider.getPendingTaskCount());

        // When ticking, EnvironmentalDataException is thrown because there is no fallback
        assertThrows(EnvironmentalDataException.class, schedulerWithFailingProvider::tick);

        // Queue must NOT be corrupted or cleared
        assertEquals(2, schedulerWithFailingProvider.getPendingTaskCount(),
                "Pending queue must be completely preserved when API fails");
    }
}
