# LIFO task preemption, state persistence, and object pooling

## Issues Faced & Fixes Implemented
* **Problem:** Abruptly interrupting chunked tasks mid-execution risked losing track of fractional progress, causing state desynchronization.
  * **Fix:** Implemented precise interruption handling using timestamps to calculate elapsed chunk time, compute uncompleted work, and accurately restore remaining duration.
* **Problem:** Reusing recycled task instances via `TaskObjectPool` retained a remaining duration of zero or partial values from previous runs, causing subsequent executions to be skipped.
  * **Fix:** Updated pool checkout/recycle logic to explicitly reset the task duration.
* **Problem:** Managing nested preemption requests required proper LIFO ordering so critical tasks execute immediately and preempted tasks resume in reverse order.
  * **Fix:** Integrated an `ArrayDeque` inside `TaskScheduler` as a robust LIFO stack.