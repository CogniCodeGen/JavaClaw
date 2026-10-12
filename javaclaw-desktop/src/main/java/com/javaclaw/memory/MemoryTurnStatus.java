package com.javaclaw.memory;

/** Canonical terminal turn states; their stored labels are presentation data only. */
public enum MemoryTurnStatus {
    COMPLETED("completed"), FAILED("failed"), CANCELLED("cancelled");

    private final String storageValue;

    MemoryTurnStatus(String storageValue) { this.storageValue = storageValue; }

    public String storageValue() { return storageValue; }

    public static MemoryTurnStatus fromStorageValue(String value) {
        if (value == null) return null;
        return switch (value) {
            case "completed" -> COMPLETED;
            case "failed" -> FAILED;
            case "cancelled" -> CANCELLED;
            default -> null;
        };
    }

    public static MemoryTurnStatus fromEventType(String eventType) {
        return switch (eventType) {
            case "turn/completed" -> COMPLETED;
            case "turn/failed" -> FAILED;
            case "turn/cancelled" -> CANCELLED;
            default -> throw new IllegalArgumentException("unsupported terminal turn event");
        };
    }
}
