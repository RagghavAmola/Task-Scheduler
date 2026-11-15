# Carbon-Aware Task Scheduler (Green Cloud)

A high-performance, carbon-aware Java/Maven framework (`com.greencloud`) designed to minimize carbon emissions of cloud workloads through dynamic environmental data gating, priority-based scheduling, interruptible chunked task execution, and lock-free object pooling.

---

## Key Features

- **Carbon Intensity Gating**: Defers carbon-sensitive background workloads when real-time grid emissions exceed task-defined thresholds ($\text{gCO}_2/\text{kWh}$).
- **Preemption & Immediate Dispatch**: Preempts active background tasks upon the arrival of `CRITICAL` tasks, preserving execution state and resuming seamlessly after the critical workload completes.
- **Resilient Environmental Data Polling**: Fetches live carbon data from the UK National Grid Carbon Intensity REST API with automated fallback to cached snapshots and simulated oscillation providers during network timeouts or malformed payloads.
- **Lock-Free Object Pooling**: Eliminates garbage collection churn for short-lived tasks via `TaskObjectPool<T>`.
- **Real-Time ANSI Timeline Console**: Displays visual timelines of scheduler events with automatic plain-text degradation on terminals without ANSI support.
- **Comprehensive JUnit 5 Test Suite**: 88 unit and integration tests covering queue ordering, threshold boundaries, preemption scenarios, and API failure modes.

---

## Prerequisites

- **Java Development Kit (JDK)**: Version 17 or higher (`java -version`)
- **Apache Maven**: Version 3.8 or higher (`mvn -version`)

---

## Local Build & Test Guide

To ensure local development matches the Continuous Integration (CI) environment, use the standard Maven workflow commands detailed below.

### 1. Full Build and Test Verification (Matches CI Pipeline)
Compiles source code, compiles test sources, runs the complete JUnit 5 test suite, fails if any test fails, and packages the JAR artifact:

```bash
mvn -B clean verify
```

> **Note**: This is the exact command executed by the GitHub Actions CI pipeline on every push and pull request.

### 2. Run All Tests
To run the automated JUnit 5 test suite without packaging:

```bash
mvn test
```

### 3. Run Specific Test Classes
To run an individual test class or package:

```bash
# Run TaskScheduler tests (queue correctness, preemption scenarios, carbon gating)
mvn test -Dtest=TaskSchedulerTest

# Run EnvironmentalDataProvider tests (live REST client, JSON parsing, timeout fallback)
mvn test -Dtest=EnvironmentalDataProviderTest

# Run Object Pool tests (concurrency, capacity caps, state resets)
mvn test -Dtest=TaskObjectPoolTest

# Run Task Model tests (boundary conditions, compareTo ordering, chunk loops)
mvn test -Dtest=ComputeTaskTest
```

### 4. Compile Only (Fast Feedback)
To compile main source files or test files without running tests:

```bash
# Compile main sources
mvn compile

# Compile test sources
mvn test-compile
```

### 5. Inspect Test Reports
Surefire test execution logs and XML reports are generated in the `target/surefire-reports/` directory:

```bash
# View test summary output
cat target/surefire-reports/*.txt
```

---

## Running the Live Simulation Demonstration

To run the interactive end-to-end simulation showing oscillating grid intensity, dynamic workload submissions, preemption of background tasks, and a styled summary:

### Using Maven:
```bash
mvn compile exec:java -Dexec.mainClass="com.greencloud.Main"
```

### Using Java CLI directly:
```bash
# Build the project first
mvn clean package

# Run the compiled jar
java -cp "target/classes;target/dependency/*" com.greencloud.Main
```
*(On Linux/macOS, use a colon `:` instead of a semicolon `;` for the classpath separator).*

---

## Continuous Integration (CI) Pipeline

Automated testing is configured via GitHub Actions in [`.github/workflows/build.yml`](.github/workflows/build.yml):

- **Triggers**: Automatically runs on every `push` and `pull_request` targeting the `main` branch.
- **Environment**: Linux (`ubuntu-latest`) with Eclipse Temurin JDK 17.
- **Dependency Caching**: Maven local repository (`~/.m2/repository`) is automatically cached across runs for fast builds.
- **Verification**: Executes `mvn -B clean verify`. The build immediately fails if any test fails or compilation encounters errors.
- **Artifact Upload**: Test execution reports from `target/surefire-reports/` are uploaded on every run (`if: always()`) as a downloadable artifact named `surefire-test-reports`.
