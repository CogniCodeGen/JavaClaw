package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.StoredRun;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Host-only, single-use creation attestation; caller-provided origin attributes are not authority. */
final class MaintenanceRunProvenance {
    static final String CREATED_FIELD = "maintenanceOrigin";
    static final String ISSUER = "framework.maintenance.gateway/1";
    private static final String PERMIT_ATTRIBUTE = "framework.maintenanceOriginPermit";
    private static final Map<String, Authorized> PERMITS = new ConcurrentHashMap<>();

    private MaintenanceRunProvenance() { }

    static RunScope scopeFor(RunScope source) {
        String id = UUID.nameUUIDFromBytes((source + ":memory-maintenance")
                .getBytes(StandardCharsets.UTF_8)).toString();
        return new RunScope(source.workspaceId(), source.userId(), id);
    }

    static Permit mint(StoredRun origin, RunRequest maintenance) {
        String token = UUID.randomUUID().toString();
        // AgentClient.beginTurn adds this exact host attribute before start().
        RunRequest bound = maintenance.withAttribute("framework.managed", JsonNodeFactory.instance.booleanNode(true))
                .withAttribute(PERMIT_ATTRIBUTE, JsonNodeFactory.instance.textNode(token));
        PERMITS.put(token, new Authorized(bound, origin.snapshot().id(), origin.request().scope(),
                origin.request().source().kind(), origin.request().source().id()));
        return new Permit(bound, token);
    }

    /** Called on the original request before prepare/annotation; recovery never remints a permit. */
    static JsonNode consume(RunRequest request) {
        JsonNode supplied = request.attributes().get(PERMIT_ATTRIBUTE);
        if (supplied == null) return null;
        Authorized permit = supplied.isTextual() ? PERMITS.remove(supplied.textValue()) : null;
        if (permit == null || !permit.request().equals(request))
            throw new SecurityException("invalid or already consumed host maintenance provenance permit");
        return JsonNodeFactory.instance.objectNode().put("schemaVersion", 1).put("issuer", ISSUER)
                .put("originRunId", permit.originRunId().value())
                .put("workspaceId", permit.originScope().workspaceId()).put("userId", permit.originScope().userId())
                .put("targetThreadId", permit.originScope().sessionId())
                .put("originSourceKind", permit.originSourceKind()).put("originSourceId", permit.originSourceId())
                .put("purpose", request.linkage().correlationId());
    }

    static RunRequest withoutPermit(RunRequest request) {
        if (!request.attributes().containsKey(PERMIT_ATTRIBUTE)) return request;
        var attributes = new LinkedHashMap<>(request.attributes());
        attributes.remove(PERMIT_ATTRIBUTE);
        return new RunRequest(request.agent(), request.profile(), request.source(), request.scope(), request.inputs(),
                request.linkage(), request.permissionCeiling(), request.budget(), request.idempotencyKey(), attributes);
    }

    record Permit(RunRequest request, String token) implements AutoCloseable {
        @Override public void close() { PERMITS.remove(token); }
    }

    private record Authorized(RunRequest request, RunId originRunId, RunScope originScope,
                              String originSourceKind, String originSourceId) { }
}
