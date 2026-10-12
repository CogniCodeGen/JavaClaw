package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Generic draft metadata used by the schema-driven UI. */
public record StudioDraft(
        String id,
        String name,
        String kind,
        JsonNode document,
        long draftRevision,
        long publishedVersion,
        boolean builtin,
        Instant updatedAt) {
    public StudioDraft {
        document = document.deepCopy();
    }
    @Override public JsonNode document() { return document.deepCopy(); }
}
