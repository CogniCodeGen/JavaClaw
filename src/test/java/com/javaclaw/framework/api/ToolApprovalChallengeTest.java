package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolApprovalChallengeTest {

    @Test
    void decodesCanonicalOutputWrappedAndLegacyTopLevelEvents() {
        var approval = JsonNodeFactory.instance.objectNode();
        approval.put("tool", "sys_file_delete");
        approval.putObject("arguments").put("path", "/tmp/file");
        approval.put("fingerprint", "fingerprint");
        approval.put("kind", "DOUBLE_CONFIRM");

        var canonical = JsonNodeFactory.instance.objectNode();
        canonical.put("reason", "delete file");
        canonical.set("approval", approval);
        assertChallenge(ToolApprovalChallenge.fromEventPayload(canonical));

        var wrapped = JsonNodeFactory.instance.objectNode();
        wrapped.put("reason", "delete file");
        wrapped.putObject("output").set("approval", approval);
        assertChallenge(ToolApprovalChallenge.fromEventPayload(wrapped));

        var legacy = approval.deepCopy();
        legacy.put("description", "delete file");
        assertChallenge(ToolApprovalChallenge.fromEventPayload(legacy));
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
