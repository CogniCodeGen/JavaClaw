package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultEvictionTest {
    @Test
    void shortTextStillAccountsForEscapingBeforeTheFastPath() {
        var input = JsonNodeFactory.instance.textNode("\"".repeat(1_200));

        var result = ToolResultEviction.bound(input, "business_tool", 2_000);

        assertTrue(input.asText().length() < 2_000);
        assertTrue(input.toString().length() > 2_000);
        assertTrue(result.path("truncated").asBoolean());
        assertTrue(result.toString().length() <= 2_000);
    }

    @Test
    void defaultEvictionRespectsTheSmallerInteractionContextBudgetAndActualJsonEscaping() {
        var capabilities = JsonNodeFactory.instance.objectNode();
        capabilities.putObject("tool.result-eviction").put("enabled", true);
        capabilities.putObject("context.compaction").put("enabled", true).put("maxToolResultCharacters", 12_000);
        var request = request(capabilities);
        var input = JsonNodeFactory.instance.textNode("😀\"\\\n".repeat(8_000));

        var result = ToolResultEviction.process(input, tool("business_tool"), context(false), request);

        assertTrue(result.path("truncated").asBoolean());
        assertTrue(result.toString().length() <= 12_000);
        assertEquals(input.asText().length(), result.path("originalCharacters").asInt());
        String preview = result.path("preview").asText();
        assertTrue(input.asText().startsWith(preview));
        assertFalse(Character.isHighSurrogate(preview.charAt(preview.length() - 1)));
    }

    @Test
    void configuredLowerLimitWinsAndInternalContextReadsKeepTheirExistingSemantics() {
        var capabilities = JsonNodeFactory.instance.objectNode();
        capabilities.putObject("tool.result-eviction").put("enabled", true).put("maxCharacters", 1_000);
        capabilities.putObject("context.compaction").put("enabled", true).put("maxToolResultCharacters", 12_000);
        var request = request(capabilities);
        var input = JsonNodeFactory.instance.textNode("x".repeat(20_000));

        assertTrue(ToolResultEviction.process(input, tool("business_tool"), context(false), request).toString().length() <= 1_000);
        assertSame(input, ToolResultEviction.process(input, tool("business_tool"), context(true), request));
    }

    @Test
    void discoveryRowsRemainStructuredAndAnUnrelatedKindGetsNoBudgetExemption() {
        var source = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1)
                .put("protocol", "computer-use").put("kind", "desktop.targets").put("count", 165);
        var rows = source.putArray("targets");
        for (int index = 0; index < 165; index++) rows.addObject().put("providerId", "macos")
                .put("processId", 2228).put("targetId", "exact-" + index).put("title", "QQ".repeat(100));
        String durable = source.toString();

        var result = ToolResultEviction.bound(source, "desktop_session_targets", 12_000);

        assertEquals("desktop.targets", result.path("kind").asText());
        assertTrue(result.path("targets").isArray());
        assertTrue(result.path("targets").size() > 0);
        assertTrue(result.path("targets").size() < 165);
        for (int index = 0; index < result.path("targets").size(); index++)
            assertEquals(rows.get(index), result.path("targets").get(index));
        assertTrue(result.toString().length() <= 12_000);
        assertEquals(durable, source.toString());
        var unrelated = ToolResultEviction.bound(source, "plugin_business_tool", 12_000);
        assertTrue(unrelated.has("preview"));
        assertFalse(unrelated.has("targets"));
        assertTrue(unrelated.toString().length() <= 12_000);
    }

    private static RunRequest request(com.fasterxml.jackson.databind.JsonNode capabilities) {
        return RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session")).inputs(java.util.List.of(InputBlock.text("task")))
                .attributes(Map.of("framework.compiledCapabilities", capabilities)).build();
    }

    private static ToolDescriptor tool(String name) {
        return new ToolDescriptor(name, "test", JsonNodeFactory.instance.objectNode().put("type", "object"),
                "test", PermissionSet.NONE, true);
    }

    private static ToolExecutionContext context(boolean internal) {
        return new ToolExecutionContext(new RunId("run"), "invocation", () -> false,
                Instant.now().plusSeconds(30), null, internal);
    }
}
