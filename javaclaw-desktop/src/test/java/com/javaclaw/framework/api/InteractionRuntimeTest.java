package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InteractionRuntimeTest {
    @Test
    void roleRequiresEveryHostIdentityComponent() {
        assertTrue(InteractionRuntime.isInteraction(request(InteractionRuntime.AGENT_ID,
                InteractionRuntime.PROFILE_ID, InteractionRuntime.SOURCE_TYPE)));
        assertFalse(InteractionRuntime.isInteraction(null));
        assertFalse(InteractionRuntime.isMain(null));
        for (var request : java.util.List.of(
                request("pretender", InteractionRuntime.PROFILE_ID, InteractionRuntime.SOURCE_TYPE),
                request(InteractionRuntime.AGENT_ID, "chat", InteractionRuntime.SOURCE_TYPE),
                request(InteractionRuntime.AGENT_ID, InteractionRuntime.PROFILE_ID, "chat"))) {
            assertFalse(InteractionRuntime.isInteraction(request));
            assertTrue(InteractionRuntime.allowedModes(request).isEmpty());
        }
    }

    @Test
    void onlyExplicitExecutableModesEnterAnImmutableSet() {
        var request = request(InteractionRuntime.AGENT_ID, InteractionRuntime.PROFILE_ID, InteractionRuntime.SOURCE_TYPE);
        assertTrue(InteractionRuntime.allowedModes(request).isEmpty());
        assertTrue(InteractionRuntime.allowedModes(request.withAttribute(InteractionRuntime.ALLOWED_MODES_ATTRIBUTE,
                JsonNodeFactory.instance.textNode("DESKTOP"))).isEmpty());
        var modes = JsonNodeFactory.instance.arrayNode().add("DESKTOP").add("BROWSER")
                .add("AUTO").add("HYBRID").add("unknown").add(1).addNull().add("DESKTOP");
        var allowed = InteractionRuntime.allowedModes(request.withAttribute(InteractionRuntime.ALLOWED_MODES_ATTRIBUTE, modes));
        assertEquals(Set.of(InteractionMode.DESKTOP, InteractionMode.BROWSER), allowed);
        assertThrows(UnsupportedOperationException.class, () -> allowed.add(InteractionMode.AUTO));
    }

    @Test
    void childIdentityIsStableAndBoundToAllThreeScopeComponents() {
        var parent = new RunScope("workspace", "user", "session");
        var child = InteractionRuntime.childScope(parent);
        assertEquals(child, InteractionRuntime.childScope(parent));
        assertEquals(parent.workspaceId(), child.workspaceId());
        assertEquals(parent.userId(), child.userId());
        assertNotEquals(child.sessionId(), parent.sessionId());
        for (var other : java.util.List.of(new RunScope("other", "user", "session"),
                new RunScope("workspace", "other", "session"), new RunScope("workspace", "user", "other"))) {
            assertNotEquals(child.sessionId(), InteractionRuntime.childScope(other).sessionId());
        }
    }

    private static RunRequest request(String agent, String profile, String source) {
        return RunRequest.builder().agent(AgentDefinitionRef.latest(agent))
                .profile(RunProfileRef.latest(profile)).source(new InvocationSource(source, "interaction-executor"))
                .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("验证身份"))
                .attributes(Map.of()).build();
    }
}
