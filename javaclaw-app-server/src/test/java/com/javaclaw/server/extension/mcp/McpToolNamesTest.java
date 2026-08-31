package com.javaclaw.server.extension.mcp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolNamesTest {
    @Test
    void allocatesStableSafeNamesAndHashesNormalizationCollisions() {
        var names = McpToolNames.allocate("server/id", List.of("read.file", "read/file"));

        assertNotEquals(names.get("read.file"), names.get("read/file"));
        names.values().forEach(name -> {
            assertTrue(name.matches("[A-Za-z0-9_-]+"));
            assertTrue(name.length() <= 200);
            assertTrue(name.startsWith("mcp__server_id__tool__read_file__"));
        });
    }

    @Test
    void usesTheFixedSyntheticCapabilityNames() {
        assertEquals("mcp__alpha__resources_read", McpToolNames.synthetic("alpha", "resources_read"));
        assertEquals("mcp__alpha__completion_complete", McpToolNames.synthetic("alpha", "completion_complete"));
    }
}
