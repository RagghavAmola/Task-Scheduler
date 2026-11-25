# Core task model and polymorphic execution

## Issues Faced & Fixes Implemented
* **Problem:** Ensuring `equals` and `hashCode` consistency without breaking distinct task identities in collections.
  * **Fix:** Fully implemented `equals` and `hashCode` using identity and state fields, ensuring safe behavior in priority queues.