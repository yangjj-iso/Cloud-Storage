package com.cloudchunk.common.exception;

public class TranscodeException extends RuntimeException {

    private final boolean retryable;

    public TranscodeException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public TranscodeException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public static TranscodeException retryable(String message) {
        return new TranscodeException(message, true);
    }

    public static TranscodeException retryable(String message, Throwable cause) {
        return new TranscodeException(message, cause, true);
    }

    public static TranscodeException fatal(String message) {
        return new TranscodeException(message, false);
    }
}
