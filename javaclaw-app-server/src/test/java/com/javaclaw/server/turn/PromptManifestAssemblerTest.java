package com.javaclaw.server.turn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PromptSourceKind;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.instructions.ProjectInstructionResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptManifestAssemblerTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 预览摘要与Turn快照一致且不返回项目约定正文() throws Exception {
        Path globalRoot = Files.createDirectories(temporaryDirectory.resolve("managed"));
        Path workspaceRoot = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Files.writeString(globalRoot.resolve("AGENTS.md"), "不可通过管理 RPC 返回的全局正文", StandardCharsets.UTF_8);
        Files.writeString(workspaceRoot.resolve("AGENTS.md"), "不可通过管理 RPC 返回的项目正文", StandardCharsets.UTF_8);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var instructions = new ProjectInstructionResolver(globalRoot, clock)
                .resolve(workspaceRoot, workspaceRoot, Optional.empty());
        PromptManifestAssembler assembler = new PromptManifestAssembler("Core 模板");
        CanonicalJson json = new CanonicalJson();
        AgentRole role = role();

        var preview = assembler.preview(configuration(role), instructions, json);

        assertEquals(
                json.encode(assembler.snapshot(configuration(role), instructions))
                        .sha256(),
                preview.manifestDigest());
        assertEquals(new AgentRoleRef("profile", 2), preview.role());
        assertEquals(6, preview.sources().size());
        assertEquals(PromptSourceKind.MODEL_BASE, preview.sources().getFirst().kind());
        assertTrue(preview.estimatedInputTokens() > 0);
        String wire = json.encode(preview).json();
        assertFalse(wire.contains("全局正文"));
        assertFalse(wire.contains("项目正文"));
        assertTrue(wire.contains("Role 指令"));
    }

    private static ResolvedAgentConfiguration configuration(AgentRole role) {
        return new ResolvedAgentConfiguration(
                role,
                new ProviderRef("provider", 3, "model"),
                new PermissionProfileRef("standard", 1),
                com.javaclaw.api.ApprovalPolicy.NONE,
                new TurnBudget(8000, 2000, 16, 2, Duration.ofMinutes(5)),
                com.javaclaw.server.TurnContractFixtures.TOOL_CATALOG.permissionCeiling(),
                com.javaclaw.api.PermissionConstraint.INHERIT,
                Optional.empty(),
                Optional.empty(),
                java.util.List.of());
    }

    private static AgentRole role() {
        return new AgentRole(
                "profile",
                2,
                RoleLifecycle.ACTIVE,
                new AgentRoleSpec(
                        "预览 Profile",
                        "",
                        "Role 指令",
                        Optional.empty(),
                        Optional.empty(),
                        new com.javaclaw.api.CapabilityNarrowing(
                                Optional.of(java.util.Set.of("tool_search")), Optional.empty()),
                        com.javaclaw.api.PermissionConstraint.INHERIT,
                        java.util.Map.of()),
                false,
                NOW,
                NOW);
    }
}
