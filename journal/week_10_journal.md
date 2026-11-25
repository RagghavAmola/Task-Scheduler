# Post-Run Analytics & Dashboard Integration

## Issues Faced & Fixes Implemented
* **Problem:** Resumed tasks showed `EXECUTED` timestamps earlier than their `RESUMED` timestamps, causing a backward dip on the **Task Executed** markers in the post-run analytics chart and misordering chronological events in the log.

  * **Fix:** Passed `System.currentTimeMillis()` at task completion in `executeCritical` and `tick` to `MetricsCollector`, aligning `EXECUTED` timestamps with actual completion time.