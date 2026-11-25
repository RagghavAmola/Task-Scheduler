# Simulation Engine / Demo Driver & Real-Time Console Visualization

## Issues Faced & Fixes Implemented
* **Problem:** Multiple background tasks trying to print messages at the exact same time caused messy, overlapping text in the console.
  * **Fix:** Added safety features (`volatile` settings and counters) so the text prints neatly and in the correct order.
* **Problem:** When testing with very short run times, tasks launched all at once instead of spreading out nicely.
  * **Fix:** Adjusted task launch times to scale based on the total simulation length so they spread out properly no matter how long the test is.