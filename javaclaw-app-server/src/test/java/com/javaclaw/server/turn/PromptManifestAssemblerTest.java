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

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.PromptSourceKind;
import com.javaclaw.api.ProviderRef;
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
        AgentProfile profile = profile();

        var preview = assembler.preview(profile, instructions, json);

        assertEquals(json.encode(assembler.snapshot(profile, instructions)).sha256(), preview.manifestDigest());
        assertEquals(new AgentProfileRef("profile", 2), preview.profile());
        assertEquals(4, preview.sources().size());
        assertEquals(
                PromptSourceKind.CORE_TEMPLATE, preview.sources().getFirst().kind());
        assertTrue(preview.estimatedInputTokens() > 0);
        String wire = json.encode(preview).json();
        assertFalse(wire.contains("全局正文"));
        assertFalse(wire.contains("项目正文"));
        assertTrue(wire.contains("Profile 指令"));
    }

    private static AgentProfile profile() {
        return new AgentProfile(
                "profile",
                2,
                ProfileLifecycle.ACTIVE,
                new AgentProfileSpec(
                        "预览 Profile",
                        "Profile 指令",
                        new ProviderRef("provider", 3, "model"),
                        new PermissionProfileRef("standard", 1),
                        java.util.Set.of("tool_search"),
                        new TurnBudget(8_000, 2_000, 16, 2, Duration.ofMinutes(5))),
                NOW,
                NOW);
    }
}
