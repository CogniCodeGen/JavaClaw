package com.javaclaw.api;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationProvenanceContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.random();

    @Test
    void provider配置按模型声明用途并拒绝不安全配置() {
        ProviderModelSpec chat = model("model-a", Set.of(ProviderModelPurpose.CHAT));
        ProviderModelSpec embedding = new ProviderModelSpec(
                "embed-a", "Embed A", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.of(1536));
        ProviderAdapterOptions.OpenAiCompatible options =
                new ProviderAdapterOptions.OpenAiCompatible(Optional.of("team"), Optional.empty());
        ProviderEndpointSpec spec = providerSpec(
                Optional.of(URI.create("https://models.example.test/v1")),
                List.of(chat, embedding),
                Optional.of(new CredentialRef("provider", "credential")),
                Duration.ofSeconds(30),
                2,
                options);

        assertEquals(List.of(chat, embedding), spec.models());
        assertEquals("team", options.organization().orElseThrow());
        assertInvalidProviderUri("relative/path");
        assertInvalidProviderUri("file:///tmp/model");
        assertInvalidProviderUri("https://user@models.example.test/v1");
        assertInvalidProviderUri("https://models.example.test/v1#fragment");
        assertInvalidProviderUri("https://models.example.test/v1?key=value");
        assertInvalidProvider(
                List.of(chat),
                Optional.of(new CredentialRef("mcp", "credential")),
                Duration.ofSeconds(1),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        assertInvalidProvider(List.of(chat), Optional.empty(), Duration.ZERO, 0, options);
        assertInvalidProvider(
                List.of(chat), Optional.empty(), Duration.ofMinutes(10).plusNanos(1), 0, options);
        assertInvalidProvider(List.of(chat), Optional.empty(), Duration.ofSeconds(1), -1, options);
        assertInvalidProvider(List.of(chat), Optional.empty(), Duration.ofSeconds(1), 11, options);
        assertInvalidProvider(List.of(chat, chat), Optional.empty(), Duration.ofSeconds(1), 0, options);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelSpec("chat", "Chat", Set.of(ProviderModelPurpose.CHAT), OptionalInt.of(3)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderEndpointSpec(
                        "Provider",
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        Optional.empty(),
                        ProviderAuthentication.NONE,
                        List.of(),
                        Optional.empty(),
                        Duration.ofSeconds(1),
                        0,
                        options));
    }

    @Test
    void 项目约定来源只暴露相对路径摘要和计数() {
        InstructionSourceResolution global = source(
                InstructionScope.GLOBAL, "AGENTS.md", Optional.of(ApiFixtures.DIGEST), 20, 20, false, Optional.empty());
        InstructionSourceResolution project = source(
                InstructionScope.PROJECT,
                "module/AGENTS.override.md",
                Optional.of("b".repeat(64)),
                40,
                32,
                true,
                Optional.empty());
        InstructionSourceResolution failed = source(
                InstructionScope.PROJECT,
                "broken/AGENTS.md",
                Optional.empty(),
                10,
                0,
                false,
                Optional.of("READ_FAILED"));
        InstructionResolution resolution = new InstructionResolution(
                List.of(global, project, failed),
                "c".repeat(64),
                20,
                32,
                List.of("TRUNCATED", "TRUNCATED", "READ_FAILED"),
                NOW);

        assertEquals("module/AGENTS.override.md", project.relativePath());
        assertEquals(2, resolution.warnings().size());
        assertEquals(32, resolution.projectIncludedBytes());
        assertEquals(
                "module/AGENTS.md",
                source(
                                InstructionScope.PROJECT,
                                "module\\AGENTS.md",
                                Optional.of(ApiFixtures.DIGEST),
                                1,
                                1,
                                false,
                                Optional.empty())
                        .relativePath());
    }

    @Test
    void 项目约定拒绝越界路径和不一致汇总() {
        for (String path : List.of("/AGENTS.md", "../AGENTS.md", "..", "module/../AGENTS.md")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> source(
                            InstructionScope.PROJECT,
                            path,
                            Optional.of(ApiFixtures.DIGEST),
                            1,
                            1,
                            false,
                            Optional.empty()));
        }
        assertInvalidSource(Optional.of(ApiFixtures.DIGEST), -1, 0, false, Optional.empty());
        assertInvalidSource(Optional.of(ApiFixtures.DIGEST), 1, -1, false, Optional.empty());
        assertInvalidSource(Optional.of(ApiFixtures.DIGEST), 1, 2, false, Optional.empty());
        assertInvalidSource(Optional.of(ApiFixtures.DIGEST), 1, 1, false, Optional.of("READ_FAILED"));
        assertInvalidSource(Optional.empty(), 1, 0, false, Optional.empty());
        assertInvalidSource(Optional.empty(), 1, 1, false, Optional.of("READ_FAILED"));
        assertInvalidSource(Optional.empty(), 1, 0, true, Optional.of("READ_FAILED"));
        assertInvalidSource(Optional.of(ApiFixtures.DIGEST), 1, 1, true, Optional.empty());

        InstructionSourceResolution global = source(
                InstructionScope.GLOBAL, "AGENTS.md", Optional.of(ApiFixtures.DIGEST), 2, 2, false, Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InstructionResolution(List.of(global), ApiFixtures.DIGEST, 1, 0, List.of(), NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InstructionResolution(List.of(global), ApiFixtures.DIGEST, 2, -1, List.of(), NOW));
    }

    @Test
    void prompt预览固定来源引用摘要和估算器() {
        PromptSourceMetadata core = new PromptSourceMetadata(
                PromptSourceKind.CORE_TEMPLATE,
                "core/chat",
                Optional.of("5.0"),
                Optional.of(ApiFixtures.DIGEST),
                20,
                List.of());
        PromptSourceMetadata instruction = new PromptSourceMetadata(
                PromptSourceKind.PROJECT_INSTRUCTION,
                "module/AGENTS.md",
                Optional.empty(),
                Optional.empty(),
                0,
                List.of("READ_FAILED", "READ_FAILED"));
        PromptManifestPreview preview = new PromptManifestPreview(
                profileRef(),
                providerRef(),
                permissionRef(),
                List.of(core, instruction),
                "b".repeat(64),
                128,
                "utf8-bytes-div-4",
                "You are JavaClaw.",
                "");

        assertEquals(2, preview.sources().size());
        assertEquals(1, instruction.warnings().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PromptSourceMetadata(
                        PromptSourceKind.SKILL, "skill", Optional.empty(), Optional.empty(), 1, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PromptSourceMetadata(
                        PromptSourceKind.SKILL, " ", Optional.empty(), Optional.of(ApiFixtures.DIGEST), 1, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PromptManifestPreview(
                        profileRef(),
                        providerRef(),
                        permissionRef(),
                        List.of(),
                        ApiFixtures.DIGEST,
                        0,
                        "estimator",
                        "template",
                        ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PromptManifestPreview(
                        profileRef(),
                        providerRef(),
                        permissionRef(),
                        List.of(),
                        ApiFixtures.DIGEST,
                        1,
                        " ",
                        "template",
                        ""));
    }

    @Test
    void profile与绑定冻结精确Provider权限和预算() {
        AgentProfileSpec spec = profileSpec();
        AgentProfile profile =
                new AgentProfile("profile_default", 2, ProfileLifecycle.ACTIVE, spec, NOW, NOW.plusSeconds(1));
        ProfileBinding workspaceBinding = new ProfileBinding(WORKSPACE_ID, Optional.empty(), profileRef(), 3, NOW);
        ProfileBinding threadBinding =
                new ProfileBinding(WORKSPACE_ID, Optional.of(ThreadId.random()), profileRef(), 4, NOW);

        assertEquals("Default", profile.spec().displayName());
        assertTrue(spec.visibleTools().contains("read_file"));
        assertTrue(workspaceBinding.threadId().isEmpty());
        assertTrue(threadBinding.threadId().isPresent());
        assertThrows(
                NullPointerException.class,
                () -> new AgentProfileSpec("Default", "", providerRef(), permissionRef(), null, ApiFixtures.budget()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentProfile(
                        "profile_default", 2, ProfileLifecycle.ARCHIVED, spec, NOW, NOW.minusSeconds(1)));
    }

    @Test
    void toolCatalog按名称排序搜索并生成稳定摘要() {
        ToolDescriptor write = ApiFixtures.tool("write_file", "保存内容", Set.of("filesystem", "write"));
        ToolDescriptor read = ApiFixtures.tool("read_file", "读取内容", Set.of("filesystem", "read"));
        ToolCatalogSnapshot first = catalog(TurnId.random(), List.of(write, read), NOW);
        ToolCatalogSnapshot second = catalog(TurnId.random(), List.of(read, write), NOW.plusSeconds(5));

        assertEquals(List.of("read_file", "write_file"), names(first.tools()));
        assertEquals("read_file", first.search("read", 10).getFirst().identity().name());
        assertEquals("write_file", first.search("保存", 10).getFirst().identity().name());
        assertEquals(2, first.search("filesystem", 10).size());
        assertEquals(read, first.require(read.identity()));
        assertEquals(first.digest(), second.digest());
        assertNotEquals(first.turnId(), second.turnId());
        assertThrows(IllegalArgumentException.class, () -> first.search("read", 0));
        assertThrows(IllegalArgumentException.class, () -> first.search("read", 101));
        assertThrows(IllegalArgumentException.class, () -> first.require(new ToolIdentity("core", "missing", 1)));
        assertThrows(IllegalArgumentException.class, () -> catalog(TurnId.random(), List.of(read, read), NOW));
    }

    @Test
    void automation快照与Worktree产物保留内容寻址证据() {
        ToolCatalogSnapshot catalog =
                catalog(TurnId.random(), List.of(ApiFixtures.tool("read_file", "读取", Set.of())), NOW);
        AutomationExecutionSnapshot snapshot = new AutomationExecutionSnapshot(
                profileRef(), providerRef(), permissionRef(), ApiFixtures.budget(), catalog, Optional.empty());
        UnattendedExecutionScope scope =
                new UnattendedExecutionScope(WORKSPACE_ID, "schedule_daily", 3, "occurrence_20260901");
        AutomationExecutionSnapshot scheduled = snapshot.withUnattendedExecutionScope(scope);
        AttachmentRef attachment = new AttachmentRef(ApiFixtures.DIGEST, "text/x-diff", "worktree.patch", 12);
        ManagedWorktreeArtifact artifact = new ManagedWorktreeArtifact(
                worktreeId(), 3, ManagedWorktreeArtifactKind.PATCH, attachment, "A".repeat(40), NOW);

        assertEquals(catalog, snapshot.toolCatalog());
        assertTrue(snapshot.unattendedExecutionScope().isEmpty());
        assertEquals(scope, scheduled.unattendedExecutionScope().orElseThrow());
        assertEquals(scheduled, scheduled.withUnattendedExecutionScope(scope));
        assertThrows(
                IllegalStateException.class,
                () -> scheduled.withUnattendedExecutionScope(
                        new UnattendedExecutionScope(WORKSPACE_ID, "schedule_daily", 3, "occurrence_next")));
        assertEquals("a".repeat(40), artifact.baseCommit());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagedWorktreeArtifact(
                        worktreeId(), 3, ManagedWorktreeArtifactKind.BACKUP, attachment, "short", NOW));
    }

    @Test
    void vault与Provider状态只返回脱敏就绪信息() {
        VaultStatus ready = new VaultStatus(VaultState.READY, VaultLockReason.NONE, 2, false, NOW);
        VaultStatus locked =
                new VaultStatus(VaultState.LOCKED, VaultLockReason.SYSTEM_CREDENTIAL_UNAVAILABLE, 2, true, NOW);
        ProviderStatus status = new ProviderStatus(
                providerRef(),
                ProviderReadiness.READY,
                new ProviderCapabilities(Set.of(ProviderModelPurpose.CHAT), true, true, true, true, true, true, true),
                Optional.of("  "),
                NOW);

        assertEquals(2, ready.credentialCount());
        assertTrue(locked.oldKeyCleanupPending());
        assertTrue(status.detail().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new VaultStatus(VaultState.READY, VaultLockReason.SYSTEM_CREDENTIAL_UNAVAILABLE, 0, false, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VaultStatus(VaultState.LOCKED, VaultLockReason.NONE, 0, false, NOW));
    }

    @Test
    void workspace项目约定Fallback只接受安全Basename() {
        WorkspaceInstructionSettings settings =
                new WorkspaceInstructionSettings(WORKSPACE_ID, Optional.of("PROJECT.instructions.md"), 2, NOW);
        WorkspaceInstructionSettings normalized =
                new WorkspaceInstructionSettings(WORKSPACE_ID, Optional.of("  PROJECT.md  "), 2, NOW);

        assertEquals("PROJECT.instructions.md", settings.fallbackBasename().orElseThrow());
        assertEquals("PROJECT.md", normalized.fallbackBasename().orElseThrow());
        for (String invalid : List.of("../AGENTS.md", "dir/AGENTS.md", "", "x".repeat(121))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new WorkspaceInstructionSettings(WORKSPACE_ID, Optional.of(invalid), 2, NOW));
        }
    }

    private static ProviderEndpointSpec providerSpec(
            Optional<URI> baseUri,
            List<ProviderModelSpec> models,
            Optional<CredentialRef> credential,
            Duration timeout,
            int retries,
            ProviderAdapterOptions options) {
        return new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                baseUri,
                ProviderAuthentication.API_KEY,
                models,
                credential,
                timeout,
                retries,
                options);
    }

    private static void assertInvalidProviderUri(String uri) {
        assertThrows(
                IllegalArgumentException.class,
                () -> providerSpec(
                        Optional.of(URI.create(uri)),
                        List.of(model("model", Set.of(ProviderModelPurpose.CHAT))),
                        Optional.empty(),
                        Duration.ofSeconds(1),
                        0,
                        ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE)));
    }

    private static void assertInvalidProvider(
            List<ProviderModelSpec> models,
            Optional<CredentialRef> credential,
            Duration timeout,
            int retries,
            ProviderAdapterOptions options) {
        assertThrows(
                RuntimeException.class,
                () -> providerSpec(Optional.empty(), models, credential, timeout, retries, options));
    }

    private static ProviderModelSpec model(String id, Set<ProviderModelPurpose> purposes) {
        return new ProviderModelSpec(id, id, purposes, OptionalInt.empty());
    }

    private static InstructionSourceResolution source(
            InstructionScope scope,
            String path,
            Optional<String> digest,
            long byteCount,
            long includedBytes,
            boolean truncated,
            Optional<String> errorCode) {
        return new InstructionSourceResolution(scope, path, digest, byteCount, includedBytes, truncated, errorCode);
    }

    private static void assertInvalidSource(
            Optional<String> digest,
            long byteCount,
            long includedBytes,
            boolean truncated,
            Optional<String> errorCode) {
        assertThrows(
                IllegalArgumentException.class,
                () -> source(
                        InstructionScope.PROJECT, "AGENTS.md", digest, byteCount, includedBytes, truncated, errorCode));
    }

    private static AgentProfileSpec profileSpec() {
        return new AgentProfileSpec(
                "Default",
                "Answer clearly.",
                providerRef(),
                permissionRef(),
                Set.of("read_file", "write_file"),
                ApiFixtures.budget());
    }

    private static ToolCatalogSnapshot catalog(TurnId turnId, List<ToolDescriptor> tools, Instant capturedAt) {
        return new ToolCatalogSnapshot(turnId, 7, tools, ApiFixtures.profile("permission", 3), capturedAt);
    }

    private static List<String> names(List<ToolDescriptor> tools) {
        return tools.stream().map(tool -> tool.identity().name()).toList();
    }

    private static AgentProfileRef profileRef() {
        return new AgentProfileRef("profile_default", 2);
    }

    private static ProviderRef providerRef() {
        return new ProviderRef("provider_openai", 3, "gpt-example");
    }

    private static PermissionProfileRef permissionRef() {
        return new PermissionProfileRef("permission", 3);
    }

    private static WorktreeId worktreeId() {
        return WorktreeId.parse(UUID.randomUUID().toString());
    }
}
