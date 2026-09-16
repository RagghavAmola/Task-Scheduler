package com.greencloud.exceptions;

/**
 * Custom checked exception for handling environmental data provider errors
 * (e.g. network failures, HTTP status errors, or JSON parsing issues).
 */
public class EnvironmentalDataException extends Exception {

    public EnvironmentalDataException(String message) {
        super(message);
    }

    public EnvironmentalDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
