# Continuous Integration & Delivery Pipeline

## Issues Faced & Fixes Implemented
* **Problem:** Code might work fine on a developer's computer, but fail on the GitHub automated testing server because of slight differences in how they ran tests.
  * **Fix:** Added clear instructions in the README file showing the exact test command (`mvn -B clean verify`) used by GitHub so local testing matches the server.