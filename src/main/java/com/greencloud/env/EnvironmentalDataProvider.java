package com.greencloud.env;

import com.greencloud.exceptions.EnvironmentalDataException;

/**
 * Interface defining the contract for environmental data providers that furnish carbon intensity snapshots.
 */
public interface EnvironmentalDataProvider {

    /**
     * Fetches the current carbon intensity snapshot.
     *
     * @return CarbonIntensitySnapshot containing region, intensity in gCO2/kWh, and timestamp
     * @throws EnvironmentalDataException if an error occurs while fetching or processing environmental data
     */
    CarbonIntensitySnapshot getCurrentIntensity() throws EnvironmentalDataException;
}
