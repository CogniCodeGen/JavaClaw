package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolEffectPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedCapabilityRegistryTest {
    @Test
    void onlyAnExactHostToolContractCanBindItsSchemasAndRisk() {
        var catalog = TrustedCapabilityRegistry.builtins();
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        var host = new ToolDescriptor("sys_file_write", "write", schema,
                "system", PermissionSet.of("tool.execute"), false, ToolEffectPolicy.LEGACY);
        var binding = catalog.bindHostTool(host);
        assertEquals("CONFIRM", binding.risk());
        assertEquals(schema, binding.inputSchema());
        assertTrue(binding.outputSchema().path("properties").has("status"));
        assertEquals(java.util.Set.of("status", "data", "errorCode", "displayMessage"),
                java.util.stream.StreamSupport.stream(
                        binding.outputSchema().path("required").spliterator(), false)
                        .map(com.fasterxml.jackson.databind.JsonNode::asText)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(TrustedCapabilityRegistry.VerifierPolicy.FILE_POSTCONDITION,
                catalog.find("file.write").orElseThrow().verifierPolicy());
        assertEquals(binding, catalog.bindHostTool(host));

        var spoofed = new ToolDescriptor("sys_file_write", "write", schema,
                "plugin", PermissionSet.NONE, true, ToolEffectPolicy.LEGACY);
        assertThrows(IllegalArgumentException.class, () -> catalog.bindHostTool(spoofed));
    }

    @Test
    void staticTargetBindingsComeFromExactHostCapabilityMetadata() {
        var catalog = TrustedCapabilityRegistry.builtins();
        var file = catalog.metadataForTool("sys_file_copy").orElseThrow();
        assertEquals("file.copy", file.id());
        assertEquals("copy", file.operation());
        assertEquals("target", file.targetArgument());
        assertEquals(com.javaclaw.framework.api.CapabilityMetadata.TargetKind.FILE,
                file.targetKind());
        assertEquals(com.javaclaw.framework.api.CapabilityMetadata.TargetSource.DECLARED,
                catalog.metadataForTool("desktop_session_observe").orElseThrow().targetSource());
        var notification = catalog.metadataForTool("notify_custom_webhook").orElseThrow();
        assertEquals("notification.send", notification.id());
        assertEquals("send", notification.operation());
        assertEquals("custom", notification.fixedTarget());
        assertEquals(com.javaclaw.framework.spi.EffectReceiptV1.Status.ACCEPTED,
                notification.evidenceCeiling());
        assertTrue(catalog.metadataForTool("plugin_claim_write").isEmpty());
    }
}
