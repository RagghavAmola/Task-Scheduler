package com.greencloud.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskSchedulerTest {

    @Test
    void testSchedulerInitialization() {
        TaskScheduler scheduler = new TaskScheduler();
        assertNotNull(scheduler);
    }
}
