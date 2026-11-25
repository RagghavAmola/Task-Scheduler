package com.greencloud.ui;

/**
 * Exception thrown when loading or parsing results CSV files fails.
 */
public class ResultsLoadingException extends Exception {

    public ResultsLoadingException(String message) {
        super(message);
    }

    public ResultsLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}
