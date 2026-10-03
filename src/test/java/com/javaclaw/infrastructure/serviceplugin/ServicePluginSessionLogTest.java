package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.serviceplugin.ServicePluginLogEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServicePluginSessionLogTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void logKindComesFromDedicatedPayloadField() throws Exception {
        ServicePluginLogEntry entry = ServicePluginSession.parseLogEvent(frame(
                "RUNTIME", "[INFERENCE_CALL] quoted by a process"));

        assertEquals(ServicePluginLogEntry.Kind.RUNTIME, entry.kind());
        assertEquals("[INFERENCE_CALL] quoted by a process", entry.text());
        assertEquals(ServicePluginLogEntry.Kind.INFERENCE_INVOCATION,
                ServicePluginSession.parseLogEvent(frame(
                        "INFERENCE_INVOCATION", "request accepted")).kind());
    }

    @Test
    void invalidLogKindDoesNotFallBackToMessageInspection() {
        assertThrows(IOException.class, () -> ServicePluginSession.parseLogEvent(
                frame("anything", "[INFERENCE_CALL] request accepted")));
    }

    private ServicePluginWire.Frame frame(String kind, String message) {
        return ServicePluginWire.Frame.control(ServicePluginWire.Type.LOG,
                42, "fixture", "1.0", json.valueToTree(
                        Map.of("kind", kind, "message", message)));
    }
}
