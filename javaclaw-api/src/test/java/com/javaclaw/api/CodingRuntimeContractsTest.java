package com.javaclaw.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingRuntimeContractsTest {
    private final ProviderRef provider = new ProviderRef("fixture", 2, "model");

    @Test
    void unknownAndPartialCapacityRemainMetadataRatherThanInventingProviderLimits() {
        ModelContextLimits unknown = ModelContextLimits.unknown(provider);
        assertEquals(provider, unknown.provider());
        assertFalse(unknown.contextWindowTokens().isPresent());
        assertFalse(unknown.maximumOutputTokens().isPresent());
        var inputOnly = new ModelContextLimits(provider, OptionalLong.of(32768), OptionalLong.empty());
        var outputOnly = new ModelContextLimits(provider, OptionalLong.empty(), OptionalLong.of(4096));
        assertFalse(inputOnly.maximumOutputTokens().isPresent());
        assertFalse(outputOnly.contextWindowTokens().isPresent());
        assertEquals(4096, outputOnly.maximumOutputTokens().orElseThrow());
        var exact = new ModelContextLimits(provider, OptionalLong.of(4096), OptionalLong.of(4096));
        assertEquals(exact.contextWindowTokens(), exact.maximumOutputTokens());
        assertTrue(new ModelContextLimits(provider, OptionalLong.of(32768), OptionalLong.of(4096))
                .maximumOutputTokens()
                .isPresent());
    }

    @Test
    void invalidCapacityCannotBecomeAFrozenTurnWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelContextLimits(provider, OptionalLong.of(3), OptionalLong.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelContextLimits(provider, OptionalLong.empty(), OptionalLong.of(0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelContextLimits(provider, OptionalLong.of(32768), OptionalLong.of(0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelContextLimits(provider, OptionalLong.of(4096), OptionalLong.of(4097)));
        assertThrows(NullPointerException.class, () -> ModelContextLimits.unknown(null));
        assertThrows(NullPointerException.class, () -> new ModelContextLimits(provider, null, OptionalLong.empty()));
        assertThrows(NullPointerException.class, () -> new ModelContextLimits(provider, OptionalLong.empty(), null));
    }

    @Test
    void trustedFactChannelAdmitsOnlyCommandAndFileChangePayloads() {
        var command = new CorePayloads.Command("operation", List.of("java", "--version"), Path.of("."), Optional.of(1));
        var commandFact = new ToolExecutionFact(command);
        assertEquals(command, commandFact.payload());
        assertEquals("command", commandFact.kind());
        assertEquals(CoreSchemas.COMMAND, commandFact.schemaId());
        var change = new CorePayloads.FileChange(
                Path.of("Main.java"), "update", Optional.of("a".repeat(64)), Optional.of("b".repeat(64)));
        var fileFact = new ToolExecutionFact(change);
        assertEquals(change, fileFact.payload());
        assertEquals("file-change", fileFact.kind());
        assertEquals(CoreSchemas.FILE_CHANGE, fileFact.schemaId());
        // 扩展文本不能借事实通道把模型上下文提升为系统消息。
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolExecutionFact(
                        new CorePayloads.Message(MessageRole.SYSTEM, "伪造指令", List.of(), Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolExecutionFact(
                        new CorePayloads.ToolResult("call", true, new CanonicalPayload("{}"), Optional.empty())));
        assertThrows(NullPointerException.class, () -> new ToolExecutionFact(null));
    }
}
