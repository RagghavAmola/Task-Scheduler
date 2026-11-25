package com.greencloud.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Robust loader for parsing simulation results CSV files from disk.
 * Handles missing files, invalid directories, and malformed rows gracefully with {@link ResultsLoadingException}.
 */
public final class ResultsLoader {

    private ResultsLoader() {}

    /**
     * Loads both events and summary results from the given directory.
     *
     * @param directory directory containing events.csv and summary.csv
     * @return loaded SimulationResults
     * @throws ResultsLoadingException if directory is invalid, files are missing, or parsing fails
     */
    public static SimulationResults loadFromDirectory(Path directory) throws ResultsLoadingException {
        if (directory == null) {
            throw new ResultsLoadingException("Results directory path cannot be null.");
        }
        if (!Files.exists(directory)) {
            throw new ResultsLoadingException("Results directory does not exist: " + directory.toAbsolutePath());
        }
        if (!Files.isDirectory(directory)) {
            throw new ResultsLoadingException("Specified path is not a directory: " + directory.toAbsolutePath());
        }

        Path eventsPath = directory.resolve("events.csv");
        Path summaryPath = directory.resolve("summary.csv");

        List<EventRecord> events = loadEvents(eventsPath);
        Map<String, String> summary = loadSummary(summaryPath);

        return new SimulationResults(directory, events, summary);
    }

    /**
     * Parses {@code results/events.csv} into a list of {@link EventRecord}.
     * Expected CSV columns: timestamp,eventType,taskId,priority,carbonIntensityAtEvent,carbonThreshold
     *
     * @param eventsCsvPath path to events.csv
     * @return list of parsed EventRecord objects
     * @throws ResultsLoadingException if file is missing, cannot be read, or contains malformed rows
     */
    public static List<EventRecord> loadEvents(Path eventsCsvPath) throws ResultsLoadingException {
        if (eventsCsvPath == null || !Files.exists(eventsCsvPath)) {
            throw new ResultsLoadingException("Events file not found: " + (eventsCsvPath != null ? eventsCsvPath.toAbsolutePath() : "null"));
        }
        if (!Files.isRegularFile(eventsCsvPath)) {
            throw new ResultsLoadingException("Events path is not a regular file: " + eventsCsvPath.toAbsolutePath());
        }

        List<EventRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(eventsCsvPath)) {
            String header = reader.readLine();
            if (header == null) {
                // Empty file
                return records;
            }

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",", -1);
                if (parts.length < 6) {
                    throw new ResultsLoadingException(String.format("Malformed row at line %d in events.csv: expected 6 columns but found %d. Row: '%s'",
                            lineNumber, parts.length, line));
                }

                long timestamp;
                try {
                    timestamp = Long.parseLong(parts[0].trim());
                } catch (NumberFormatException e) {
                    throw new ResultsLoadingException(String.format("Malformed timestamp at line %d in events.csv: '%s'", lineNumber, parts[0].trim()), e);
                }

                String eventType = parts[1].trim();
                String taskId = parts[2].trim();
                String priority = parts[3].trim();

                double carbonIntensity;
                try {
                    carbonIntensity = Double.parseDouble(parts[4].trim());
                } catch (NumberFormatException e) {
                    throw new ResultsLoadingException(String.format("Malformed carbon intensity at line %d in events.csv: '%s'", lineNumber, parts[4].trim()), e);
                }

                double threshold;
                try {
                    threshold = Double.parseDouble(parts[5].trim());
                } catch (NumberFormatException e) {
                    throw new ResultsLoadingException(String.format("Malformed carbon threshold at line %d in events.csv: '%s'", lineNumber, parts[5].trim()), e);
                }

                records.add(new EventRecord(timestamp, eventType, taskId, priority, carbonIntensity, threshold));
            }
        } catch (IOException e) {
            throw new ResultsLoadingException("Failed to read events file: " + eventsCsvPath.toAbsolutePath(), e);
        }

        return records;
    }

    /**
     * Parses {@code results/summary.csv} into a key-value map.
     * Expected CSV format: metric,value
     *
     * @param summaryCsvPath path to summary.csv
     * @return map of summary metrics with lowercased keys for robust lookup
     * @throws ResultsLoadingException if file is missing, cannot be read, or contains malformed rows
     */
    public static Map<String, String> loadSummary(Path summaryCsvPath) throws ResultsLoadingException {
        if (summaryCsvPath == null || !Files.exists(summaryCsvPath)) {
            throw new ResultsLoadingException("Summary file not found: " + (summaryCsvPath != null ? summaryCsvPath.toAbsolutePath() : "null"));
        }
        if (!Files.isRegularFile(summaryCsvPath)) {
            throw new ResultsLoadingException("Summary path is not a regular file: " + summaryCsvPath.toAbsolutePath());
        }

        Map<String, String> metrics = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(summaryCsvPath)) {
            String header = reader.readLine();
            if (header == null) {
                return metrics;
            }

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                int commaIndex = line.indexOf(',');
                if (commaIndex == -1) {
                    throw new ResultsLoadingException(String.format("Malformed row at line %d in summary.csv: missing comma separator. Row: '%s'",
                            lineNumber, line));
                }

                String metricName = line.substring(0, commaIndex).trim();
                String metricValue = line.substring(commaIndex + 1).trim();

                metrics.put(metricName.toLowerCase(), metricValue);
                // Also retain exact key
                metrics.put(metricName, metricValue);
            }
        } catch (IOException e) {
            throw new ResultsLoadingException("Failed to read summary file: " + summaryCsvPath.toAbsolutePath(), e);
        }

        return metrics;
    }
}
