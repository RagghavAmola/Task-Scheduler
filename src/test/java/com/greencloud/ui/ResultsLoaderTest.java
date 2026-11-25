package com.greencloud.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResultsLoader Unit Tests")
class ResultsLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should successfully load valid events.csv and summary.csv")
    void testLoadValidResults() throws IOException, ResultsLoadingException {
        Path eventsFile = tempDir.resolve("events.csv");
        Path summaryFile = tempDir.resolve("summary.csv");

        List<String> eventLines = List.of(
                "timestamp,eventType,taskId,priority,carbonIntensityAtEvent,carbonThreshold",
                "1700000000000,SUBMITTED,bg-task-1,BACKGROUND,150.00,200.00",
                "1700000001000,EXECUTED,bg-task-1,BACKGROUND,140.50,200.00",
                "1700000002000,COMPLETED,bg-task-1,BACKGROUND,135.20,200.00"
        );
        Files.write(eventsFile, eventLines);

        List<String> summaryLines = List.of(
                "metric,value",
                "carbon-aware execution rate,100.00%",
                "preemption success rate,100.00%",
                "avg background wait time,100.00 ms",
                "avg critical time-to-execution,5.00 ms",
                "total submitted,1",
                "total completed,1",
                "total preempted,0",
                "total failed,0"
        );
        Files.write(summaryFile, summaryLines);

        SimulationResults results = ResultsLoader.loadFromDirectory(tempDir);
        assertNotNull(results);
        assertEquals(tempDir, results.directory());

        List<EventRecord> events = results.events();
        assertEquals(3, events.size());

        EventRecord first = events.get(0);
        assertEquals(1700000000000L, first.timestamp());
        assertEquals("SUBMITTED", first.eventType());
        assertEquals("bg-task-1", first.taskId());
        assertEquals("BACKGROUND", first.priority());
        assertEquals(150.00, first.carbonIntensityAtEvent(), 0.001);
        assertEquals(200.00, first.carbonThreshold(), 0.001);

        EventRecord second = events.get(1);
        assertEquals("EXECUTED", second.eventType());
        assertEquals(140.50, second.carbonIntensityAtEvent(), 0.001);

        assertEquals("100.00%", results.getMetric("carbon-aware execution rate"));
        assertEquals("100.00%", results.getMetric("preemption success rate"));
        assertEquals("100.00 ms", results.getMetric("avg background wait time"));
        assertEquals("1", results.getMetric("total submitted"));
        assertEquals("1", results.getMetric("total completed"));
        assertEquals("N/A", results.getMetric("non_existent_key"));
    }

    @Test
    @DisplayName("Should throw ResultsLoadingException when directory does not exist")
    void testMissingDirectory() {
        Path nonExistent = tempDir.resolve("non_existent_dir");
        ResultsLoadingException ex = assertThrows(ResultsLoadingException.class, () ->
                ResultsLoader.loadFromDirectory(nonExistent));
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    @Test
    @DisplayName("Should throw ResultsLoadingException when events.csv is missing")
    void testMissingEventsFile() throws IOException {
        Path summaryFile = tempDir.resolve("summary.csv");
        Files.write(summaryFile, List.of("metric,value", "total submitted,1"));

        ResultsLoadingException ex = assertThrows(ResultsLoadingException.class, () ->
                ResultsLoader.loadFromDirectory(tempDir));
        assertTrue(ex.getMessage().contains("Events file not found"));
    }

    @Test
    @DisplayName("Should throw ResultsLoadingException when summary.csv is missing")
    void testMissingSummaryFile() throws IOException {
        Path eventsFile = tempDir.resolve("events.csv");
        Files.write(eventsFile, List.of(
                "timestamp,eventType,taskId,priority,carbonIntensityAtEvent,carbonThreshold",
                "1700000000000,SUBMITTED,t1,BACKGROUND,120.0,200.0"
        ));

        ResultsLoadingException ex = assertThrows(ResultsLoadingException.class, () ->
                ResultsLoader.loadFromDirectory(tempDir));
        assertTrue(ex.getMessage().contains("Summary file not found"));
    }

    @Test
    @DisplayName("Should throw ResultsLoadingException for malformed column counts in events.csv")
    void testMalformedEventsColumnCount() throws IOException {
        Path eventsFile = tempDir.resolve("events.csv");
        Files.write(eventsFile, List.of(
                "timestamp,eventType,taskId,priority,carbonIntensityAtEvent,carbonThreshold",
                "1700000000000,SUBMITTED,t1,BACKGROUND" // only 4 columns
        ));

        ResultsLoadingException ex = assertThrows(ResultsLoadingException.class, () ->
                ResultsLoader.loadEvents(eventsFile));
        assertTrue(ex.getMessage().contains("expected 6 columns"));
    }

    @Test
    @DisplayName("Should throw ResultsLoadingException for non-numeric fields in events.csv")
    void testMalformedEventsNonNumeric() throws IOException {
        Path eventsFile = tempDir.resolve("events.csv");
        Files.write(eventsFile, List.of(
                "timestamp,eventType,taskId,priority,carbonIntensityAtEvent,carbonThreshold",
                "invalid_timestamp,SUBMITTED,t1,BACKGROUND,120.0,200.0"
        ));

        ResultsLoadingException ex = assertThrows(ResultsLoadingException.class, () ->
                ResultsLoader.loadEvents(eventsFile));
        assertTrue(ex.getMessage().contains("Malformed timestamp"));
    }

    @Test
    @DisplayName("Should throw ResultsLoadingException for rows missing comma in summary.csv")
    void testMalformedSummaryMissingComma() throws IOException {
        Path summaryFile = tempDir.resolve("summary.csv");
        Files.write(summaryFile, List.of(
                "metric,value",
                "this_line_has_no_comma_at_all"
        ));

        ResultsLoadingException ex = assertThrows(ResultsLoadingException.class, () ->
                ResultsLoader.loadSummary(summaryFile));
        assertTrue(ex.getMessage().contains("missing comma separator"));
    }
}
