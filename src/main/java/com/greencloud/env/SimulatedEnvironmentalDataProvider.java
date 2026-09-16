package com.greencloud.env;

import com.greencloud.exceptions.EnvironmentalDataException;

import java.time.Instant;
import java.util.Random;

/**
 * Simulated environmental data provider that produces pattern-based carbon intensity values.
 * Uses an oscillating sine wave pattern combined with slight pseudo-random variance to simulate
 * daily grid carbon intensity fluctuations (e.g. low intensity during solar peak, high during evening peak).
 */
public class SimulatedEnvironmentalDataProvider implements EnvironmentalDataProvider {

    private final String region;
    private final double minIntensity;
    private final double maxIntensity;
    private final double periodSeconds;
    private final boolean addRandomNoise;
    private final Random random;

    /**
     * Default constructor creating a simulated provider for region "SIMULATED-UK"
     * oscillating between 80.0 gCO2/kWh and 320.0 gCO2/kWh with a 60-second period.
     */
    public SimulatedEnvironmentalDataProvider() {
        this("SIMULATED-UK", 80.0, 320.0, 60.0, true);
    }

    public SimulatedEnvironmentalDataProvider(String region, double minIntensity, double maxIntensity, double periodSeconds, boolean addRandomNoise) {
        this.region = region;
        this.minIntensity = minIntensity;
        this.maxIntensity = maxIntensity;
        this.periodSeconds = periodSeconds;
        this.addRandomNoise = addRandomNoise;
        this.random = new Random();
    }

    @Override
    public CarbonIntensitySnapshot getCurrentIntensity() throws EnvironmentalDataException {
        long currentMillis = System.currentTimeMillis();
        double elapsedSeconds = (currentMillis / 1000.0) % periodSeconds;
        double phase = (elapsedSeconds / periodSeconds) * 2.0 * Math.PI;

        // Sine wave oscillation between minIntensity and maxIntensity
        double midPoint = (minIntensity + maxIntensity) / 2.0;
        double amplitude = (maxIntensity - minIntensity) / 2.0;
        double baseValue = midPoint + amplitude * Math.sin(phase);

        // Add slight random noise (+/- 5%) if enabled
        double noise = addRandomNoise ? (random.nextDouble() - 0.5) * 0.1 * amplitude : 0.0;
        double finalIntensity = Math.max(0.0, Math.round((baseValue + noise) * 100.0) / 100.0);

        return new CarbonIntensitySnapshot(region, finalIntensity, Instant.now());
    }
}
