package com.greencloud.env;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greencloud.exceptions.ApiException;
import com.greencloud.exceptions.EnvironmentalDataException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Live environmental data provider that fetches real-time carbon intensity data
 * from the UK National Grid Carbon Intensity API (https://api.carbonintensity.org.uk/intensity)
 * or a user-specified endpoint using Java 11+ HttpClient and Jackson JSON parser.
 * <p>
 * Hardened to catch network timeouts, connection failures, HTTP errors, and malformed
 * JSON payloads, wrapping them in {@link EnvironmentalDataException}. Falls back to the
 * last known good {@link CarbonIntensitySnapshot} or a secondary {@link EnvironmentalDataProvider}
 * (such as {@link SimulatedEnvironmentalDataProvider}) to prevent scheduler crashes.
 */
public class LiveEnvironmentalDataProvider implements EnvironmentalDataProvider {

    private static final Logger LOGGER = Logger.getLogger(LiveEnvironmentalDataProvider.class.getName());

    public static final String DEFAULT_UK_API_URL = "https://api.carbonintensity.org.uk/intensity";

    private final String apiUrl;
    private final String region;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile EnvironmentalDataProvider fallbackProvider;
    private volatile CarbonIntensitySnapshot lastKnownSnapshot;

    public LiveEnvironmentalDataProvider() {
        this(DEFAULT_UK_API_URL, "UK-GRID");
    }

    public LiveEnvironmentalDataProvider(String apiUrl, String region) {
        this(apiUrl, region, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(), null);
    }

    public LiveEnvironmentalDataProvider(EnvironmentalDataProvider fallbackProvider) {
        this(DEFAULT_UK_API_URL, "UK-GRID", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(), fallbackProvider);
    }

    public LiveEnvironmentalDataProvider(String apiUrl, String region, EnvironmentalDataProvider fallbackProvider) {
        this(apiUrl, region, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(), fallbackProvider);
    }

    public LiveEnvironmentalDataProvider(String apiUrl, String region, HttpClient httpClient, ObjectMapper objectMapper) {
        this(apiUrl, region, httpClient, objectMapper, null);
    }

    public LiveEnvironmentalDataProvider(String apiUrl, String region, HttpClient httpClient, ObjectMapper objectMapper, EnvironmentalDataProvider fallbackProvider) {
        this.apiUrl = apiUrl;
        this.region = region;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.fallbackProvider = fallbackProvider;
    }

    @Override
    public CarbonIntensitySnapshot getCurrentIntensity() throws EnvironmentalDataException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new ApiException(
                        String.format("Carbon intensity API returned HTTP status %d: %s", response.statusCode(), response.body())
                );
            }

            CarbonIntensitySnapshot snapshot = parseResponse(response.body());
            this.lastKnownSnapshot = snapshot;
            return snapshot;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return handleFailure(new EnvironmentalDataException("Interrupted while fetching live carbon intensity data", e));
        } catch (HttpTimeoutException e) {
            return handleFailure(new EnvironmentalDataException("Network timeout while contacting carbon intensity API: " + e.getMessage(), e));
        } catch (ConnectException e) {
            return handleFailure(new EnvironmentalDataException("Connection failure while contacting carbon intensity API: " + e.getMessage(), e));
        } catch (IOException e) {
            return handleFailure(new ApiException("Network I/O failure while fetching carbon intensity data: " + e.getMessage(), e));
        } catch (EnvironmentalDataException ede) {
            return handleFailure(ede);
        } catch (Exception e) {
            return handleFailure(new EnvironmentalDataException("Failed to process carbon intensity response: " + e.getMessage(), e));
        }
    }

    private CarbonIntensitySnapshot parseResponse(String jsonBody) throws EnvironmentalDataException {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonBody);
            JsonNode dataNode = rootNode.path("data");

            if (!dataNode.isArray() || dataNode.isEmpty()) {
                throw new ApiException("Invalid API response format: missing 'data' array");
            }

            JsonNode currentItem = dataNode.get(0);
            JsonNode intensityNode = currentItem.path("intensity");

            double intensityValue;
            if (intensityNode.hasNonNull("actual")) {
                intensityValue = intensityNode.path("actual").asDouble();
            } else if (intensityNode.hasNonNull("forecast")) {
                intensityValue = intensityNode.path("forecast").asDouble();
            } else {
                throw new ApiException("No valid 'actual' or 'forecast' carbon intensity found in response");
            }

            String fromTimestamp = currentItem.path("from").asText();
            Instant timestamp = Instant.now();
            if (fromTimestamp != null && !fromTimestamp.isBlank()) {
                try {
                    timestamp = Instant.parse(fromTimestamp);
                } catch (Exception e1) {
                    try {
                        timestamp = java.time.ZonedDateTime.parse(fromTimestamp, java.time.format.DateTimeFormatter.ISO_DATE_TIME).toInstant();
                    } catch (Exception ignored) {
                        // Fallback to Instant.now() if date parsing fails
                    }
                }
            }

            return new CarbonIntensitySnapshot(region, intensityValue, timestamp);
        } catch (JsonProcessingException e) {
            throw new EnvironmentalDataException("Malformed JSON received from carbon intensity API: " + e.getMessage(), e);
        } catch (EnvironmentalDataException ede) {
            throw ede;
        } catch (Exception e) {
            throw new EnvironmentalDataException("JSON parsing failure for carbon intensity response: " + e.getMessage(), e);
        }
    }

    private CarbonIntensitySnapshot handleFailure(EnvironmentalDataException failureException) throws EnvironmentalDataException {
        LOGGER.log(Level.WARNING, "Live environmental data retrieval failed: " + failureException.getMessage());

        // 1. Fall back to last known good snapshot if available
        if (lastKnownSnapshot != null) {
            LOGGER.info(() -> String.format("[FALLBACK] Using last known good snapshot (%.1f gCO2/kWh, region: %s)",
                    lastKnownSnapshot.carbonIntensityGCO2kWh(), lastKnownSnapshot.region()));
            return lastKnownSnapshot;
        }

        // 2. Fall back to secondary EnvironmentalDataProvider if configured
        if (fallbackProvider != null) {
            LOGGER.info(() -> "[FALLBACK] Delegating to fallback EnvironmentalDataProvider (" + fallbackProvider.getClass().getSimpleName() + ")");
            return fallbackProvider.getCurrentIntensity();
        }

        // 3. Otherwise propagate the wrapped EnvironmentalDataException
        throw failureException;
    }

    public CarbonIntensitySnapshot getLastKnownSnapshot() {
        return lastKnownSnapshot;
    }

    public void setLastKnownSnapshot(CarbonIntensitySnapshot lastKnownSnapshot) {
        this.lastKnownSnapshot = lastKnownSnapshot;
    }

    public EnvironmentalDataProvider getFallbackProvider() {
        return fallbackProvider;
    }

    public void setFallbackProvider(EnvironmentalDataProvider fallbackProvider) {
        this.fallbackProvider = fallbackProvider;
    }
}
