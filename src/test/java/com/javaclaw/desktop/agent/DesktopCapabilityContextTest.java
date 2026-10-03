package com.javaclaw.desktop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopCapabilityContextTest {
    @TempDir Path temporary;

    @Test
    void savedSwitchIsReadFreshWithoutAnyNativeStatusOrInputCall() {
        AtomicBoolean enabled = new AtomicBoolean(false);
        DesktopCapabilityContext context = new DesktopCapabilityContext(enabled::get);
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("liveSessionIds")) return java.util.Optional.empty();
                    throw new AssertionError("runtime context must not call desktop service: " + method.getName());
                });
        DesktopSessionTools tools = new DesktopSessionTools(sessions,
                new DesktopSessionOwner("workspace", "thread", "chat", "source"),
                temporary, null, null, context);

        JsonNode off = tools.currentContext().getFirst();
        assertFalse(off.path("settingEnabled").asBoolean());
        assertEquals("desktop.access.current", off.path("kind").asText());
        assertEquals("NOT_CHECKED", off.path("systemStatus").asText());
        assertTrue(off.toString().length() <= 350, "context must fit compact planner budgets");

        enabled.set(true);
        JsonNode on = tools.currentContext().getFirst();
        assertTrue(on.path("settingEnabled").asBoolean());
        assertEquals("NOT_CHECKED", on.path("systemStatus").asText());
        assertTrue(on.path("instruction").asText().contains("desktop_session_probe"));
        assertTrue(on.path("instruction").asText().contains("overrides old assistant access claims"));
        assertTrue(on.toString().length() <= 350, "context must fit compact planner budgets");

        enabled.set(false);
        assertFalse(tools.currentContext().getFirst().path("settingEnabled").asBoolean());
    }

    @Test
    void changingOneSnapshotCannotChangeLaterCurrentState() {
        DesktopCapabilityContext context = new DesktopCapabilityContext(() -> true);
        ((ObjectNode) context.currentContext().getFirst()).put("settingEnabled", false);
        assertTrue(context.currentContext().getFirst().path("settingEnabled").asBoolean());
    }

    @Test
    void legacyDesktopConstructorHasNoRuntimeContext() {
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("liveSessionIds")) return java.util.Optional.empty();
                    throw new AssertionError("unexpected desktop call: " + method.getName());
                });
        DesktopSessionTools tools = new DesktopSessionTools(sessions,
                new DesktopSessionOwner("workspace", "thread", "chat", "source"), temporary);
        assertTrue(tools.currentContext().isEmpty());
    }
}
