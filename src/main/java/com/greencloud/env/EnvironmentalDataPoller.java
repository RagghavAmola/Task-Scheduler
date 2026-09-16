package com.greencloud.env;

import com.greencloud.exceptions.EnvironmentalDataException;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically polls an EnvironmentalDataProvider using a ScheduledExecutorService
 * and caches the latest CarbonIntensitySnapshot.
 * Allows schedulers to inspect the current carbon intensity instantly without blocking
 * on network calls.
 */
public class EnvironmentalDataPoller implements EnvironmentalDataProvider, AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(EnvironmentalDataPoller.class.getName());

    private final EnvironmentalDataProvider provider;
    private final ScheduledExecutorService executor;
    private final long pollIntervalSeconds;
    private final boolean ownExecutor;
    private final AtomicReference<CarbonIntensitySnapshot> latestSnapshot = new AtomicReference<>();

    public EnvironmentalDataPoller(EnvironmentalDataProvider provider, long pollIntervalSeconds) {
        this(provider, pollIntervalSeconds, Executors.newSingleThreadScheduledExecutor(), true);
    }

    public EnvironmentalDataPoller(EnvironmentalDataProvider provider, long pollIntervalSeconds, ScheduledExecutorService executor, boolean ownExecutor) {
        this.provider = provider;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.executor = executor;
        this.ownExecutor = ownExecutor;
    }

    /**
     * Starts the periodic polling task. Performs an initial poll immediately.
     */
    public synchronized void start() {
        // Initial synchronous poll to ensure a snapshot is immediately available
        poll();

        executor.scheduleWithFixedDelay(
                this::poll,
                pollIntervalSeconds,
                pollIntervalSeconds,
                TimeUnit.SECONDS
        );
        LOGGER.info(() -> String.format("EnvironmentalDataPoller started with interval of %d seconds", pollIntervalSeconds));
    }

    /**
     * Polls the underlying provider and updates the cached snapshot.
     */
    public void poll() {
        try {
            CarbonIntensitySnapshot snapshot = provider.getCurrentIntensity();
            latestSnapshot.set(snapshot);
            LOGGER.fine(() -> String.format("Updated carbon intensity snapshot: %.1f gCO2/kWh (%s)",
                    snapshot.carbonIntensityGCO2kWh(), snapshot.region()));
        } catch (EnvironmentalDataException e) {
            LOGGER.log(Level.WARNING, "Failed to poll environmental data provider: " + e.getMessage(), e);
            // Retain last known snapshot if polling fails
        }
    }

    /**
     * Returns the cached carbon intensity snapshot without blocking on a network call.
     * If no snapshot has been cached yet, attempts an on-demand poll.
     *
     * @return CarbonIntensitySnapshot latest cached snapshot
     * @throws EnvironmentalDataException if no snapshot is cached and on-demand poll fails
     */
    @Override
    public CarbonIntensitySnapshot getCurrentIntensity() throws EnvironmentalDataException {
        CarbonIntensitySnapshot cached = latestSnapshot.get();
        if (cached != null) {
            return cached;
        }
        // Fallback if no snapshot cached yet
        poll();
        cached = latestSnapshot.get();
        if (cached == null) {
            throw new EnvironmentalDataException("No carbon intensity snapshot available yet");
        }
        return cached;
    }

    public CarbonIntensitySnapshot getLatestSnapshot() {
        return latestSnapshot.get();
    }

    /**
     * Stops the polling executor service cleanly.
     */
    public synchronized void stop() {
        if (ownExecutor && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOGGER.info("EnvironmentalDataPoller stopped");
        }
    }

    @Override
    public void close() {
        stop();
    }
}
