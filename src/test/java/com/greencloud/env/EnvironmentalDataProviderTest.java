package com.greencloud.env;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greencloud.exceptions.ApiException;
import com.greencloud.exceptions.EnvironmentalDataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EnvironmentalDataProviderTest {

    @Test
    @DisplayName("Test SimulatedEnvironmentalDataProvider returns valid snapshots within expected intensity range")
    void testSimulatedProvider() throws EnvironmentalDataException {
        SimulatedEnvironmentalDataProvider provider = new SimulatedEnvironmentalDataProvider("SIM-TEST", 100.0, 300.0, 10.0, false);
        CarbonIntensitySnapshot snapshot = provider.getCurrentIntensity();

        assertNotNull(snapshot);
        assertEquals("SIM-TEST", snapshot.region());
        assertTrue(snapshot.carbonIntensityGCO2kWh() >= 100.0 && snapshot.carbonIntensityGCO2kWh() <= 300.0,
                "Intensity should be between 100 and 300, got: " + snapshot.carbonIntensityGCO2kWh());
        assertNotNull(snapshot.timestamp());
    }

    @Test
    @DisplayName("Test LiveEnvironmentalDataProvider parses valid UK Carbon Intensity API JSON correctly")
    void testLiveProviderParsing() throws Exception {
        String mockJsonResponse = """
                {
                "data": [
                        {
                        "from": "2026-09-16T18:00Z",
                        "to": "2026-09-16T18:30Z",
                        "intensity": {
                        "forecast": 135,
                        "actual": 128,
                        "index": "moderate"
                        }
                        }
                ]
                }
                """;

        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(mockJsonResponse);

        doReturn(mockResponse).when(mockClient).send(any(HttpRequest.class), any());

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper()
        );

        CarbonIntensitySnapshot snapshot = provider.getCurrentIntensity();

        assertNotNull(snapshot);
        assertEquals("UK-TEST", snapshot.region());
        assertEquals(128.0, snapshot.carbonIntensityGCO2kWh());
        assertEquals(Instant.parse("2026-09-16T18:00:00Z"), snapshot.timestamp());
    }

    @Test
    @DisplayName("Test LiveEnvironmentalDataProvider wraps HTTP errors in EnvironmentalDataException")
    void testLiveProviderHttpError() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("Internal Server Error");

        doReturn(mockResponse).when(mockClient).send(any(HttpRequest.class), any());

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper()
        );

        EnvironmentalDataException ex = assertThrows(EnvironmentalDataException.class, provider::getCurrentIntensity);
        assertTrue(ex.getMessage().contains("HTTP status 500"));
    }

    @Test
    @DisplayName("Test EnvironmentalDataPoller background caching and non-blocking retrieval")
    void testPollerCachingAndErrorRetention() throws Exception {
        EnvironmentalDataProvider mockProvider = mock(EnvironmentalDataProvider.class);
        CarbonIntensitySnapshot snapshot1 = new CarbonIntensitySnapshot("TEST", 150.0, Instant.now());

        when(mockProvider.getCurrentIntensity())
                .thenReturn(snapshot1)
                .thenThrow(new EnvironmentalDataException("Network timeout"));

        try (EnvironmentalDataPoller poller = new EnvironmentalDataPoller(mockProvider, 1)) {
            poller.start();

            // First poll should update cache
            assertEquals(150.0, poller.getCurrentIntensity().carbonIntensityGCO2kWh());

            // Second poll throws exception, poller retains last known snapshot
            poller.poll();
            assertEquals(150.0, poller.getCurrentIntensity().carbonIntensityGCO2kWh(),
                    "Poller should retain previous valid snapshot when polling fails");
        }
    }

    @Test
    @DisplayName("Test LiveEnvironmentalDataProvider wraps malformed JSON in EnvironmentalDataException")
    void testLiveProviderMalformedJsonThrowsEnvironmentalDataException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{ invalid: json ::: not valid ]");

        doReturn(mockResponse).when(mockClient).send(any(HttpRequest.class), any());

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper()
        );

        EnvironmentalDataException ex = assertThrows(EnvironmentalDataException.class, provider::getCurrentIntensity);
        assertTrue(ex.getMessage().contains("Malformed JSON") || ex.getMessage().contains("JSON parsing failure"));
    }

    @Test
    @DisplayName("Test LiveEnvironmentalDataProvider wraps network timeout in EnvironmentalDataException")
    void testLiveProviderTimeoutThrowsEnvironmentalDataException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);

        doThrow(new HttpTimeoutException("Connection timed out after 10000ms"))
                .when(mockClient).send(any(HttpRequest.class), any());

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper()
        );

        EnvironmentalDataException ex = assertThrows(EnvironmentalDataException.class, provider::getCurrentIntensity);
        assertTrue(ex.getMessage().contains("Network timeout") || ex.getMessage().contains("timed out"));
    }

    @Test
    @DisplayName("Test LiveEnvironmentalDataProvider falls back to last known good snapshot on network timeout")
    void testLiveProviderFallsBackToLastKnownGoodSnapshotOnTimeout() throws Exception {
        String validJson = """
                {
                "data": [
                        {
                        "from": "2026-09-16T18:00Z",
                        "to": "2026-09-16T18:30Z",
                        "intensity": {
                        "forecast": 120,
                        "actual": 115,
                        "index": "low"
                        }
                        }
                ]
                }
                """;

        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(validJson);

        // First call succeeds, second call throws timeout
        doReturn(mockResponse)
                .doThrow(new HttpTimeoutException("Read timed out"))
                .when(mockClient).send(any(HttpRequest.class), any());

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper()
        );

        // Call 1: establishes lastKnownSnapshot
        CarbonIntensitySnapshot snapshot1 = provider.getCurrentIntensity();
        assertEquals(115.0, snapshot1.carbonIntensityGCO2kWh());

        // Call 2: times out -> should fall back to snapshot1 without throwing
        CarbonIntensitySnapshot snapshot2 = provider.getCurrentIntensity();
        assertNotNull(snapshot2);
        assertEquals(115.0, snapshot2.carbonIntensityGCO2kWh(), "Should fall back to last known good intensity value");
    }

    @Test
    @DisplayName("Test LiveEnvironmentalDataProvider falls back to SimulatedEnvironmentalDataProvider on malformed JSON")
    void testLiveProviderFallsBackToSimulatedProviderOnMalformedJson() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{ malformed json content }");

        doReturn(mockResponse).when(mockClient).send(any(HttpRequest.class), any());

        SimulatedEnvironmentalDataProvider fallback = new SimulatedEnvironmentalDataProvider("SIM-FALLBACK", 75.0, 75.0, 60.0, false);

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper(),
                fallback
        );

        CarbonIntensitySnapshot snapshot = provider.getCurrentIntensity();
        assertNotNull(snapshot);
        assertEquals("SIM-FALLBACK", snapshot.region());
        assertEquals(75.0, snapshot.carbonIntensityGCO2kWh(), 0.01);
    }

    @Test
    @DisplayName("Test LiveEnvironmentalDataProvider handles null API response body without crashing")
    void testLiveProviderNullResponseBodyThrowsException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(null);

        doReturn(mockResponse).when(mockClient).send(any(HttpRequest.class), any());

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper()
        );

        EnvironmentalDataException ex = assertThrows(EnvironmentalDataException.class, provider::getCurrentIntensity);
        assertTrue(ex.getMessage().contains("empty or null") || ex.getMessage().contains("null"));
    }

    @ParameterizedTest(name = "[{index}] Incomplete/Malformed JSON payload: {0}")
    @ValueSource(strings = {
            "",                                                                        // Empty response
            "   ",                                                                     // Blank whitespace
            "{ }",                                                                     // Missing 'data' array
            "{\"data\": null}",                                                        // 'data' is null
            "{\"data\": []}",                                                          // 'data' is empty array
            "{\"data\": [null]}",                                                      // First data element is null
            "{\"data\": [{}]}",                                                        // Missing 'intensity' object
            "{\"data\": [{\"intensity\": null}]}",                                     // 'intensity' is null
            "{\"data\": [{\"intensity\": {\"index\": \"low\"}}]}"                      // Missing actual and forecast
    })
    @DisplayName("Test LiveEnvironmentalDataProvider rejects malformed and incomplete JSON payloads")
    void testLiveProviderIncompleteJsonPayloads(String invalidJson) throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(invalidJson);

        doReturn(mockResponse).when(mockClient).send(any(HttpRequest.class), any());

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper()
        );

        assertThrows(EnvironmentalDataException.class, provider::getCurrentIntensity,
                "Provider must wrap invalid payload in EnvironmentalDataException");
    }

    @Test
    @DisplayName("Test LiveEnvironmentalDataProvider falls back to last known snapshot when API returns null body")
    void testLiveProviderFallsBackToLastKnownSnapshotOnNullResponseBody() throws Exception {
        String validJson = """
                {
                "data": [
                        {
                        "from": "2026-09-16T18:00Z",
                        "to": "2026-09-16T18:30Z",
                        "intensity": {
                        "forecast": 140,
                        "actual": 130,
                        "index": "moderate"
                        }
                        }
                ]
                }
                """;

        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponseValid = mock(HttpResponse.class);
        when(mockResponseValid.statusCode()).thenReturn(200);
        when(mockResponseValid.body()).thenReturn(validJson);

        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponseNull = mock(HttpResponse.class);
        when(mockResponseNull.statusCode()).thenReturn(200);
        when(mockResponseNull.body()).thenReturn(null);

        // Call 1 returns valid, Call 2 returns null body
        doReturn(mockResponseValid)
                .doReturn(mockResponseNull)
                .when(mockClient).send(any(HttpRequest.class), any());

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper()
        );

        CarbonIntensitySnapshot first = provider.getCurrentIntensity();
        assertEquals(130.0, first.carbonIntensityGCO2kWh());

        // Second call should smoothly fallback without throwing
        CarbonIntensitySnapshot second = provider.getCurrentIntensity();
        assertNotNull(second);
        assertEquals(130.0, second.carbonIntensityGCO2kWh());
    }

    @Test
    @DisplayName("Test LiveEnvironmentalDataProvider falls back to fallback provider on connection refused")
    void testLiveProviderFallsBackToSecondaryProviderOnConnectionRefused() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        doThrow(new ConnectException("Connection refused to remote carbon server"))
                .when(mockClient).send(any(HttpRequest.class), any());

        SimulatedEnvironmentalDataProvider secondary = new SimulatedEnvironmentalDataProvider("SIM-CONNECT-FAILOVER", 120.0, 120.0, 10.0, false);
        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper(),
                secondary
        );

        CarbonIntensitySnapshot snapshot = provider.getCurrentIntensity();
        assertNotNull(snapshot);
        assertEquals("SIM-CONNECT-FAILOVER", snapshot.region());
        assertEquals(120.0, snapshot.carbonIntensityGCO2kWh(), 0.01);
    }

    @Test
    @DisplayName("Test LiveEnvironmentalDataProvider wraps generic IOException into ApiException")
    void testLiveProviderGenericIoException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        doThrow(new IOException("Broken pipe during HTTP transmission"))
                .when(mockClient).send(any(HttpRequest.class), any());

        LiveEnvironmentalDataProvider provider = new LiveEnvironmentalDataProvider(
                "https://api.carbonintensity.org.uk/intensity",
                "UK-TEST",
                mockClient,
                new ObjectMapper()
        );

        EnvironmentalDataException ex = assertThrows(EnvironmentalDataException.class, provider::getCurrentIntensity);
        assertInstanceOf(ApiException.class, ex);
        assertTrue(ex.getMessage().contains("Network I/O failure"));
    }
}
