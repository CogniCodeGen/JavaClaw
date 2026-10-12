package com.javaclaw.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;

/** Bounded identity checkpoint, with no DOM text, credentials or reusable input references. */
final class BrowserInteractionContext {
    private BrowserInteractionContext() { }
    static List<JsonNode> current(PlaywrightBrowserManager manager) {
        var value = JsonNodeFactory.instance.objectNode();
        value.put("kind", "browser_runtime_state");
        value.put("freshObservation", false);
        value.put("inputAuthority", false);
        value.put("activePageId", manager.activeInteractionSurfaceId());
        var surfaces = value.putArray("surfaces");
        for (var identity : manager.interactionSurfaces()) {
            var surface = surfaces.addObject();
            surface.put("runtimeId", identity.runtimeId());
            surface.put("contextId", identity.contextId());
            surface.put("pageId", identity.surfaceId());
            surface.put("documentId", identity.documentId());
            surface.put("generation", identity.generation());
            surface.put("openerPageId", identity.relatedSurfaceId());
            surface.put("relationshipProof", identity.relationProof().name());
            surface.put("origin", identity.urlOrigin());
        }
        return List.of(value);
    }
}
