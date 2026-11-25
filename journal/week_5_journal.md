# Carbon-aware constraints and execution refinement

## Issues Faced & Fixes Implemented
* **Problem:** Evaluating task eligibility across varying priorities introduced conditional overhead, and background tasks could incorrectly execute during high-carbon windows. Furthermore, asynchronous status updates risked race conditions during state transitions.
  * **Fix:** Enforced a clean priority evaluation pipeline where `CRITICAL` tasks explicitly bypass carbon-intensity checks, while non-critical tasks remain deferred in the queue until environmental thresholds are met. Standardized atomic status updates immediately prior to thread execution.