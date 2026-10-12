package com.javaclaw.framework.springai;

/** Stops a Turn before the provider sees an incomplete or unverified context selection. */
final class ContextPlanningRequiredException extends RuntimeException {
    ContextPlanningRequiredException(String message) { super(message); }
    ContextPlanningRequiredException(String message, Throwable cause) { super(message, cause); }
}
