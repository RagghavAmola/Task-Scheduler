package com.greencloud.exceptions;

/**
 * Custom runtime exception thrown when an invalid task submission or illegal scheduling operation occurs.
 * Extends RuntimeException to permit unchecked validation while supporting explicit handling.
 */
public class SchedulingException extends RuntimeException {

    public SchedulingException(String message) {
        super(message);
    }

    public SchedulingException(String message, Throwable cause) {
        super(message, cause);
    }
}
