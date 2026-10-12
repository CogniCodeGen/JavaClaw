package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolApprovalChallengeTest {

    @Test
    void decodesOnlyCanonicalStructuredEvent() {
        var approval = new ToolApprovalChallenge("sys_file_delete",
                JsonNodeFactory.instance.objectNode().put("path", "/tmp/file"),
                "fingerprint", "DOUBLE_CONFIRM", "delete file").toJson();
        var canonical = JsonNodeFactory.instance.objectNode();
        canonical.put("reason", "delete file");
        canonical.set("approval", approval);
        assertChallenge(ToolApprovalChallenge.fromEventPayload(canonical));

        var wrapped = JsonNodeFactory.instance.objectNode();
        wrapped.put("reason", "delete file");
        wrapped.putObject("output").set("approval", approval);
        assertThrows(IllegalArgumentException.class,
                () -> ToolApprovalChallenge.fromEventPayload(wrapped));

        var legacy = approval.deepCopy();
        assertThrows(IllegalArgumentException.class,
                () -> ToolApprovalChallenge.fromEventPayload(legacy));

        var missingArguments = canonical.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingArguments.path("approval"))
                .remove("arguments");
        assertThrows(IllegalArgumentException.class,
                () -> ToolApprovalChallenge.fromEventPayload(missingArguments));

        var missingTrust = canonical.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingTrust.path("approval"))
                .remove("trustedContextRead");
        assertThrows(IllegalArgumentException.class,
                () -> ToolApprovalChallenge.fromEventPayload(missingTrust));
    }

    @Test
    void trustedContextReadIsExplicitAndSurvivesTheApprovalEvent() {
        ToolApprovalChallenge challenge = new ToolApprovalChallenge("context",
                JsonNodeFactory.instance.objectNode(), "fingerprint", "CONFIRM",
                "Read context", true);
        var event = JsonNodeFactory.instance.objectNode();
        event.set("approval", challenge.toJson());
        assertEquals(true, ToolApprovalChallenge.fromEventPayload(event)
                .trustedContextRead());
    }

    @Test
    void malformedEventNeverSynthesizesAuthorization() {
        assertThrows(IllegalArgumentException.class, () ->
                ToolApprovalChallenge.fromEventPayload(
                        JsonNodeFactory.instance.objectNode().put("reason", "missing")));
    }

    private static void assertChallenge(ToolApprovalChallenge challenge) {
        assertEquals("sys_file_delete", challenge.tool());
        assertEquals("/tmp/file", challenge.arguments().path("path").asText());
        assertEquals("fingerprint", challenge.fingerprint());
        assertEquals("DOUBLE_CONFIRM", challenge.kind());
        assertEquals("delete file", challenge.description());
    }
}
