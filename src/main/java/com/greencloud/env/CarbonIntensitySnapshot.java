package com.greencloud.env;

import java.time.Instant;

public record CarbonIntensitySnapshot(
        String region,
        double carbonIntensityGCO2kWh,
        Instant timestamp
) {
}
