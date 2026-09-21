package com.javaclaw.framework.api;

/** The durable turn remains open and requires recovery before its caller may continue. */
public class TurnPausedException extends RuntimeException {
    public TurnPausedException(String message) { super(message); }
}
