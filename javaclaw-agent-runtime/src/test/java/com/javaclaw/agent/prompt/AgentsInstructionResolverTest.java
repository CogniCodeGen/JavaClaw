package com.javaclaw.agent.prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentsInstructionResolverTest {
    @TempDir
    Path temporary;

    @Test
    void resolvesGlobalOverrideAndProjectLayersInCodexOrder() throws Exception {
        Path global = Files.createDirectories(temporary.resolve("config"));
        Files.writeString(global.resolve("AGENTS.md"), "全局默认");
        Files.writeString(global.resolve("AGENTS.override.md"), "全局覆盖");
        Path root = Files.createDirectories(temporary.resolve("repo"));
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve("AGENTS.md"), "仓库规则");
        Files.writeString(root.resolve("AGENT.md"), "单数名称不得读取");
        Path module = Files.createDirectories(root.resolve("module/deep"));
        Files.writeString(root.resolve("module/AGENTS.md"), "模块默认");
        Files.writeString(root.resolve("module/AGENTS.override.md"), "模块覆盖");
        Files.writeString(module.resolve("FALLBACK.md"), "深层 fallback");

        var resolver = new AgentsInstructionResolver(
                global, () -> new AgentsInstructionSettings(List.of(".git"), List.of("FALLBACK.md"), 32_768));
        AgentsInstructionResolution result = resolver.inspect(module, Set.of(root));

        assertEquals(
                List.of("全局覆盖", "仓库规则", "模块覆盖", "深层 fallback"),
                result.sources().stream()
                        .map(AgentsInstructionResolution.Source::content)
                        .toList());
        assertEquals(
                List.of("global", "project", "project", "project"),
                result.sources().stream()
                        .map(AgentsInstructionResolution.Source::scope)
                        .toList());
        assertTrue(result.asModelContext().contains("全局覆盖\n\n--- project-doc ---\n\n仓库规则\n\n模块覆盖\n\n深层 fallback"));
        assertFalse(result.asModelContext().contains("单数名称不得读取"));
        assertFalse(result.asModelContext()
                .contains(root.resolve("module/AGENTS.override.md").toString()));
    }

    @Test
    void skipsEmptyCandidatesAndHonorsAlternativeRootMarkers() throws Exception {
        Path global = Files.createDirectories(temporary.resolve("global"));
        Path root = Files.createDirectories(temporary.resolve("marker-root"));
        Files.writeString(root.resolve("pom.xml"), "marker");
        Files.writeString(root.resolve("AGENTS.override.md"), " \n");
        Files.writeString(root.resolve("AGENTS.md"), "根规则");
        Path child = Files.createDirectories(root.resolve("child"));
        Files.writeString(child.resolve("AGENTS.md"), "子规则");

        var resolver = new AgentsInstructionResolver(
                global, () -> new AgentsInstructionSettings(List.of("pom.xml"), List.of(), 32_768));
        assertEquals(
                List.of("根规则", "子规则"),
                resolver.inspect(child, Set.of(root)).sources().stream()
                        .map(AgentsInstructionResolution.Source::content)
                        .toList());
    }

    @Test
    void withoutARootMarkerReadsOnlyTheWorkingDirectory() throws Exception {
        Path global = Files.createDirectories(temporary.resolve("global"));
        Path boundary = Files.createDirectories(temporary.resolve("workspace"));
        Files.writeString(boundary.resolve("AGENTS.md"), "不可向上读取");
        Path cwd = Files.createDirectories(boundary.resolve("plain/cwd"));
        Files.writeString(cwd.resolve("AGENTS.md"), "当前目录规则");

        var resolver = new AgentsInstructionResolver(global, AgentsInstructionSettings::defaults);
        AgentsInstructionResolution result = resolver.inspect(cwd, Set.of(boundary));
        assertEquals(
                List.of("当前目录规则"),
                result.sources().stream()
                        .map(AgentsInstructionResolution.Source::content)
                        .toList());
    }

    @Test
    void emptyRootMarkersDisableParentTraversal() throws Exception {
        Path global = Files.createDirectories(temporary.resolve("global"));
        Path root = Files.createDirectories(temporary.resolve("repo"));
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve("AGENTS.md"), "根规则");
        Path child = Files.createDirectories(root.resolve("child"));
        Files.writeString(child.resolve("AGENTS.md"), "当前规则");

        var resolver = new AgentsInstructionResolver(
                global, () -> new AgentsInstructionSettings(List.of(), List.of(), 32_768));
        assertEquals(
                List.of("当前规则"),
                resolver.inspect(child, Set.of(root)).sources().stream()
                        .map(AgentsInstructionResolution.Source::content)
                        .toList());
    }

    @Test
    void truncatesTheProjectByteBudgetAndReplacesInvalidUtf8() throws Exception {
        Path global = Files.createDirectories(temporary.resolve("global"));
        Path root = Files.createDirectories(temporary.resolve("repo"));
        Files.createDirectory(root.resolve(".git"));
        byte[] original = new byte[AgentsInstructionSettings.DEFAULT_PROJECT_DOC_MAX_BYTES + 1];
        java.util.Arrays.fill(original, (byte) 'x');
        original[0] = 'A';
        original[1] = 'B';
        original[2] = (byte) 0xC3;
        original[3] = 0x28;
        original[AgentsInstructionSettings.DEFAULT_PROJECT_DOC_MAX_BYTES - 1] = (byte) 0xC3;
        original[AgentsInstructionSettings.DEFAULT_PROJECT_DOC_MAX_BYTES] = (byte) 0xA9;
        Files.write(root.resolve("AGENTS.md"), original);

        var resolver = new AgentsInstructionResolver(global, AgentsInstructionSettings::defaults);
        AgentsInstructionResolution result = resolver.inspect(root, Set.of(root));
        assertEquals(32_768, result.totalProjectBytes());
        assertTrue(result.sources().getFirst().truncated());
        assertTrue(result.sources().getFirst().content().contains("�"));
        assertEquals(
                PromptHashes.sha256(java.util.Arrays.copyOf(original, 32_768)),
                result.sources().getFirst().sha256());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void rejectsSymlinkTargetsOutsideReadableRoots() throws Exception {
        Path global = Files.createDirectories(temporary.resolve("global"));
        Path root = Files.createDirectories(temporary.resolve("repo"));
        Files.createDirectory(root.resolve(".git"));
        Path outside = Files.writeString(temporary.resolve("outside.md"), "越界规则");
        Files.createSymbolicLink(root.resolve("AGENTS.md"), outside);

        var resolver = new AgentsInstructionResolver(global, AgentsInstructionSettings::defaults);
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> resolver.inspect(root, Set.of(root)));
        assertTrue(failure.getMessage().contains("outside readable roots"));
    }

    @Test
    void cachesPerThreadUntilItsEnvironmentOrSettingsChange() throws Exception {
        Path global = Files.createDirectories(temporary.resolve("global"));
        Path root = Files.createDirectories(temporary.resolve("repo"));
        Files.createDirectory(root.resolve(".git"));
        Path instructions = root.resolve("AGENTS.md");
        Files.writeString(instructions, "版本一");
        AtomicReference<AgentsInstructionSettings> settings =
                new AtomicReference<>(AgentsInstructionSettings.defaults());
        var resolver = new AgentsInstructionResolver(global, settings::get);
        AgentThread thread = thread(root);
        TurnConfig config = config(root, root);

        assertEquals(
                "版本一", resolver.resolve(thread, config).sources().getFirst().content());
        Files.writeString(instructions, "版本二");
        assertEquals(
                "版本一", resolver.resolve(thread, config).sources().getFirst().content());
        settings.set(new AgentsInstructionSettings(List.of(".git"), List.of("FALLBACK.md"), 32_768));
        AgentsInstructionResolution refreshed = resolver.resolve(thread, config);
        assertEquals("版本二", refreshed.sources().getFirst().content());
        assertTrue(refreshed.warnings().contains(AgentsInstructionResolution.REPLACEMENT_NOTICE));
        Files.writeString(instructions, "版本三");
        settings.set(new AgentsInstructionSettings(List.of(".git"), List.of("FALLBACK.md"), 32_768, 2));
        assertEquals(
                "版本三", resolver.resolve(thread, config).sources().getFirst().content());
        Files.delete(instructions);
        settings.set(new AgentsInstructionSettings(List.of(".git"), List.of("FALLBACK.md"), 32_768, 3));
        AgentsInstructionResolution removed = resolver.resolve(thread, config);
        assertTrue(removed.sources().isEmpty());
        assertTrue(removed.warnings().contains(AgentsInstructionResolution.REMOVAL_NOTICE));
        assertTrue(removed.asModelContext().contains(AgentsInstructionResolution.REMOVAL_NOTICE));
    }

    @Test
    void unreadableExistingFileFailsInsteadOfBeingSilentlySkipped() throws Exception {
        Path global = Files.createDirectories(temporary.resolve("global"));
        Path root = Files.createDirectories(temporary.resolve("repo"));
        Files.createDirectory(root.resolve(".git"));
        Path file = Files.writeString(root.resolve("AGENTS.md"), "安全约束");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(file);
        try {
            Files.setPosixFilePermissions(file, Set.of());
            Assumptions.assumeFalse(Files.isReadable(file));
            var resolver = new AgentsInstructionResolver(global, AgentsInstructionSettings::defaults);
            assertThrows(IllegalArgumentException.class, () -> resolver.inspect(root, Set.of(root)));
        } finally {
            Files.setPosixFilePermissions(file, original);
        }
    }

    private static AgentThread thread(Path cwd) {
        return new AgentThread(
                new ThreadId("thread"),
                "workspace",
                null,
                null,
                "",
                cwd,
                ThreadStatus.ACTIVE,
                0,
                0,
                1,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static TurnConfig config(Path cwd, Path readableRoot) {
        return new TurnConfig(
                "model",
                "fake",
                "medium",
                cwd,
                SandboxPolicy.readOnly(Set.of(readableRoot), Set.of()),
                ApprovalPolicy.ON_RISK,
                Set.of(),
                Map.of());
    }
}
