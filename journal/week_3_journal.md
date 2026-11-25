# Environmental data ingestion and caching

## Issues Faced & Fixes Implemented
* **Problem:** Handling uninitialized cache edge cases on startup when no poll has succeeded yet.
  * **Fix:** Initialized fallback checks and wrapped network/parsing failures cleanly in custom checked exceptions.