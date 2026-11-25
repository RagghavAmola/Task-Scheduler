# Core carbon-aware scheduling loop

## Issues Faced & Fixes Implemented
* **Problem:** `PriorityQueue` internal iteration order is not fully sorted, making defensive snapshot copies (`getPendingTasks()`) look unsorted.
  * **Fix:** Explicitly sorted the defensive copy inside `getPendingTasks()`.
* **Problem:** Head-of-line blocking by design causing potential starvation of eligible lower-priority tasks.
  * **Fix:** Logged behavior explicitly for preemption re-evaluation in subsequent phases.