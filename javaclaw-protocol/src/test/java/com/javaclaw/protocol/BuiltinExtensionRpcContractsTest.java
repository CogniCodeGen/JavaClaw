package com.javaclaw.protocol;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuiltinExtensionRpcContractsTest {
    @Test
    void 状态区分贡献版本和启停版本并固定贡献集合() {
        var status = new BuiltinExtensionRpcContracts.Status(
                "com.javaclaw.plan",
                "计划",
                "5.0.0",
                1,
                3,
                ExtensionAvailability.OPTIONAL,
                ExtensionState.DISABLED,
                BuiltinExtensionRpcContracts.RuntimeKind.BUNDLE,
                Set.of(ContributionKind.COMMAND, ContributionKind.VIEW),
                Instant.parse("2026-09-01T00:00:00Z"));

        assertEquals(1, status.descriptorRevision());
        assertEquals(3, status.stateRevision());
        assertThrows(
                IllegalArgumentException.class,
                () -> new BuiltinExtensionRpcContracts.Status(
                        status.id(),
                        status.displayName(),
                        status.version(),
                        1,
                        0,
                        status.availability(),
                        status.state(),
                        status.runtimeKind(),
                        status.contributionKinds(),
                        status.updatedAt()));
    }

    @Test
    void MCP平台能力与Bundle使用同一强类型结果() {
        var mcp = new BuiltinExtensionRpcContracts.Status(
                "com.javaclaw.mcp",
                "MCP",
                "5.0.0",
                1,
                1,
                ExtensionAvailability.OPTIONAL,
                ExtensionState.ENABLED,
                BuiltinExtensionRpcContracts.RuntimeKind.PLATFORM,
                Set.of(ContributionKind.MCP, ContributionKind.TOOL),
                Instant.parse("2026-09-01T00:00:00Z"));

        assertEquals(BuiltinExtensionRpcContracts.RuntimeKind.PLATFORM, mcp.runtimeKind());
        assertEquals(mcp, new BuiltinExtensionRpcContracts.StatusResult(mcp).extension());
        assertThrows(IllegalArgumentException.class, () -> new BuiltinExtensionRpcContracts.ExtensionPayload(" "));
    }
}
