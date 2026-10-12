package com.javaclaw.desktop.api;

/** Bound at tool construction to a workspace and framework session/run source. */
public record DesktopSessionOwner(String workspaceId, String scopeId, String sourceKind,
                                  String sourceId) {
    public DesktopSessionOwner {
        if (workspaceId == null || workspaceId.isBlank() || scopeId == null || scopeId.isBlank()
                || sourceKind == null || sourceKind.isBlank())
            throw new IllegalArgumentException("desktop session owner is incomplete");
        sourceId = sourceId == null ? "" : sourceId;
    }
}
