package com.greencloud.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greencloud.exceptions.EnvironmentalDataException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Live environmental data provider that fetches real-time carbon intensity data
 * from the UK National Grid Carbon Intensity API (https://api.carbonintensity.org.uk/intensity)
 * or a user-specified endpoint using Java 11+ HttpClient and Jackson JSON parser.
 */
public class LiveEnvironmentalDataProvider implements EnvironmentalDataProvider {

    public static final String DEFAULT_UK_API_URL = "https://api.carbonintensity.org.uk/intensity";

    private final String apiUrl;
    private final String region;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LiveEnvironmentalDataProvider() {
        this(DEFAULT_UK_API_URL, "UK-GRID");
    }

    public LiveEnvironmentalDataProvider(String apiUrl, String region) {
        this(apiUrl, region, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper());
    }

    public LiveEnvironmentalDataProvider(String apiUrl, String region, HttpClient httpClient, ObjectMapper objectMapper) {
        this.apiUrl = apiUrl;
        this.region = region;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
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
                throw new EnvironmentalDataException(
                        String.format("Carbon intensity API returned HTTP status %d: %s", response.statusCode(), response.body())
                );
            }

            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EnvironmentalDataException("Interrupted while fetching live carbon intensity data", e);
        } catch (IOException e) {
            throw new EnvironmentalDataException("Network I/O failure while fetching carbon intensity data: " + e.getMessage(), e);
        } catch (Exception e) {
            if (e instanceof EnvironmentalDataException ede) {
                throw ede;
            }
            throw new EnvironmentalDataException("Failed to process carbon intensity response: " + e.getMessage(), e);
        }
    }

    private CarbonIntensitySnapshot parseResponse(String jsonBody) throws EnvironmentalDataException {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonBody);
            JsonNode dataNode = rootNode.path("data");

            if (!dataNode.isArray() || dataNode.isEmpty()) {
                throw new EnvironmentalDataException("Invalid API response format: missing 'data' array");
            }

            JsonNode currentItem = dataNode.get(0);
            JsonNode intensityNode = currentItem.path("intensity");

            double intensityValue;
            if (intensityNode.hasNonNull("actual")) {
                intensityValue = intensityNode.path("actual").asDouble();
            } else if (intensityNode.hasNonNull("forecast")) {
                intensityValue = intensityNode.path("forecast").asDouble();
            } else {
                throw new EnvironmentalDataException("No valid 'actual' or 'forecast' carbon intensity found in response");
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
        } catch (Exception e) {
            if (e instanceof EnvironmentalDataException ede) {
                throw ede;
            }
            throw new EnvironmentalDataException("JSON parsing failure for carbon intensity response", e);
        }
    }
}
