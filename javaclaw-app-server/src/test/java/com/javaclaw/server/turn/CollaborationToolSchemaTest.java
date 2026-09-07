package com.javaclaw.server.turn;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollaborationToolSchemaTest {
    @Test
    void 协作工具Schema已规范化且目录持久化往返不改变摘要() {
        CanonicalJson json = new CanonicalJson();
        for (var descriptor : CollaborationTools.all()) {
            assertEquals(json.parse(descriptor.inputSchema().json()), descriptor.inputSchema());
            assertEquals(json.parse(descriptor.outputSchema().json()), descriptor.outputSchema());
        }
        ToolCatalogSnapshot snapshot = new ToolCatalogSnapshot(
                TurnId.random(),
                1,
                CollaborationTools.all(),
                TurnContractFixtures.TOOL_CATALOG.permissionCeiling(),
                java.time.Instant.EPOCH);
        ToolCatalogSnapshot decoded = json.decode(json.encode(snapshot), ToolCatalogSnapshot.class);
        assertEquals(snapshot.digest(), decoded.digest());
    }
}
