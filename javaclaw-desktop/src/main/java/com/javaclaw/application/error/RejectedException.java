package com.javaclaw.application.error;

/** 操作因配额、授权或生命周期边界被明确拒绝。 */
public class RejectedException extends RuntimeException {
    public RejectedException(String message) {
        super(message);
    }

    public RejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
