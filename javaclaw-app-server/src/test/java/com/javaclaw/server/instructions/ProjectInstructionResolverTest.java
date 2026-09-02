package com.javaclaw.server.instructions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.InstructionScope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInstructionResolverTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T09:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 按全局和目录层级选择override默认或fallback() throws Exception {
        Path global = Files.createDirectories(temporaryDirectory.resolve("state"));
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path module = Files.createDirectories(workspace.resolve("module"));
        Path execution = Files.createDirectories(module.resolve("feature"));
        Files.writeString(global.resolve("AGENTS.md"), "忽略的全局默认", StandardCharsets.UTF_8);
        Files.writeString(global.resolve("AGENTS.override.md"), "全局覆盖", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("AGENTS.md"), "工作区约定", StandardCharsets.UTF_8);
        Files.writeString(module.resolve("AGENTS.md"), "忽略的模块默认", StandardCharsets.UTF_8);
        Files.writeString(module.resolve("AGENTS.override.md"), "模块覆盖", StandardCharsets.UTF_8);
        Files.writeString(execution.resolve("PROJECT.md"), "功能约定", StandardCharsets.UTF_8);

        ResolvedInstructions resolved =
                new ProjectInstructionResolver(global, CLOCK).resolve(workspace, execution, Optional.of("PROJECT.md"));

        assertEquals(
                List.of("AGENTS.override.md", "AGENTS.md", "module/AGENTS.override.md", "module/feature/PROJECT.md"),
                resolved.resolution().sources().stream()
                        .map(source -> source.relativePath())
                        .toList());
        assertEquals(
                InstructionScope.GLOBAL,
                resolved.resolution().sources().getFirst().scope());
        assertTrue(resolved.promptContent().contains("全局覆盖"));
        assertTrue(resolved.promptContent().contains("模块覆盖"));
        assertTrue(resolved.promptContent().contains("功能约定"));
        assertFalse(resolved.promptContent().contains("忽略的全局默认"));
        assertFalse(resolved.promptContent().contains("忽略的模块默认"));
        assertTrue(resolved.resolution().sources().stream()
                .noneMatch(source -> source.relativePath().contains(temporaryDirectory.toString())));
    }

    @Test
    void 每层最多纳入32KiB并保持完整UTF8边界() throws Exception {
        Path global = Files.createDirectories(temporaryDirectory.resolve("global"));
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace-limit"));
        String oversized = "a".repeat(ProjectInstructionResolver.SCOPE_BYTE_LIMIT - 1) + "你" + "tail";
        Files.writeString(global.resolve("AGENTS.md"), oversized, StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("AGENTS.md"), oversized, StandardCharsets.UTF_8);

        ResolvedInstructions resolved =
                new ProjectInstructionResolver(global, CLOCK).resolve(workspace, workspace, Optional.empty());

        assertEquals(
                ProjectInstructionResolver.SCOPE_BYTE_LIMIT - 1,
                resolved.resolution().globalIncludedBytes());
        assertEquals(
                ProjectInstructionResolver.SCOPE_BYTE_LIMIT - 1,
                resolved.resolution().projectIncludedBytes());
        assertTrue(resolved.resolution().sources().stream().allMatch(source -> source.truncated()));
        assertTrue(resolved.resolution().warnings().contains("GLOBAL_TRUNCATED"));
        assertTrue(resolved.resolution().warnings().contains("PROJECT_TRUNCATED"));
        assertFalse(resolved.promptContent().contains("你"));
    }

    @Test
    void 非法UTF8只返回脱敏错误且不进入Prompt() throws Exception {
        Path global = Files.createDirectories(temporaryDirectory.resolve("global-invalid"));
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace-invalid"));
        Files.write(global.resolve("AGENTS.md"), new byte[] {(byte) 0xc3, 0x28});

        ResolvedInstructions resolved =
                new ProjectInstructionResolver(global, CLOCK).resolve(workspace, workspace, Optional.empty());

        assertEquals(
                Optional.of("INVALID_UTF8"),
                resolved.resolution().sources().getFirst().errorCode());
        assertTrue(resolved.resolution().sources().getFirst().digest().isEmpty());
        assertEquals("", resolved.promptContent());
        assertEquals(List.of("INVALID_UTF8"), resolved.resolution().warnings());
    }

    @Test
    void 内容变化只改变下一次解析摘要() throws Exception {
        Path global = Files.createDirectories(temporaryDirectory.resolve("global-digest"));
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace-digest"));
        Path agents = workspace.resolve("AGENTS.md");
        Files.writeString(agents, "第一版", StandardCharsets.UTF_8);
        ProjectInstructionResolver resolver = new ProjectInstructionResolver(global, CLOCK);

        String first = resolver.resolve(workspace, workspace, Optional.empty())
                .resolution()
                .digest();
        Files.writeString(agents, "第二版", StandardCharsets.UTF_8);
        String second = resolver.resolve(workspace, workspace, Optional.empty())
                .resolution()
                .digest();

        assertNotEquals(first, second);
    }

    @Test
    void 工作区目录暂不可用时保留全局约定并返回脱敏警告() throws Exception {
        Path global = Files.createDirectories(temporaryDirectory.resolve("global-available"));
        Path workspace = temporaryDirectory.resolve("workspace-missing");
        Files.writeString(global.resolve("AGENTS.md"), "全局安全约定", StandardCharsets.UTF_8);

        ResolvedInstructions resolved =
                new ProjectInstructionResolver(global, CLOCK).resolve(workspace, workspace, Optional.empty());

        assertEquals(1, resolved.resolution().sources().size());
        assertEquals(
                List.of("WORKSPACE_ROOT_UNAVAILABLE"), resolved.resolution().warnings());
        assertTrue(resolved.promptContent().contains("全局安全约定"));
    }

    @Test
    void 执行根缺失或词法越界失败而工作区内中间符号链接可解析() throws Exception {
        Path global = Files.createDirectories(temporaryDirectory.resolve("global-boundary"));
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace-boundary"));
        ProjectInstructionResolver resolver = new ProjectInstructionResolver(global, CLOCK);

        ResolvedInstructions missing = resolver.resolve(workspace, workspace.resolve("missing"), Optional.empty());
        assertEquals(List.of("EXECUTION_ROOT_UNAVAILABLE"), missing.resolution().warnings());
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(workspace, temporaryDirectory.resolve("outside"), Optional.empty()));

        Path linkedTarget = Files.createDirectories(workspace.resolve("real-inside/child"));
        Files.writeString(linkedTarget.resolve("AGENTS.md"), "链接目录约定", StandardCharsets.UTF_8);
        Files.createSymbolicLink(workspace.resolve("linked"), linkedTarget.getParent());
        ResolvedInstructions linked = resolver.resolve(workspace, workspace.resolve("linked/child"), Optional.empty());
        assertTrue(linked.promptContent().contains("链接目录约定"));
        assertEquals(
                "linked/child/AGENTS.md",
                linked.resolution().sources().getFirst().relativePath());
    }

    @Test
    void fallback名称必须安全且与默认文件重名时只读取一次() throws Exception {
        Path global = Files.createDirectories(temporaryDirectory.resolve("global-fallback"));
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace-fallback"));
        Files.writeString(workspace.resolve("AGENTS.md"), "唯一约定", StandardCharsets.UTF_8);
        ProjectInstructionResolver resolver = new ProjectInstructionResolver(global, CLOCK);

        ResolvedInstructions resolved = resolver.resolve(workspace, workspace, Optional.of("AGENTS.md"));

        assertEquals(1, resolved.resolution().sources().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(workspace, workspace, Optional.of("../unsafe.md")));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(workspace, workspace, Optional.of(" ")));
    }

    @Test
    void 文件读取器拒绝负预算目录缺失文件和符号链接() throws Exception {
        InstructionFileReader reader = new InstructionFileReader();
        Path regular = Files.writeString(temporaryDirectory.resolve("instruction.md"), "content");
        assertThrows(
                IllegalArgumentException.class,
                () -> reader.read(InstructionScope.PROJECT, regular, "instruction.md", -1));
        assertEquals(
                "NOT_REGULAR_FILE",
                reader.read(InstructionScope.PROJECT, temporaryDirectory, ".", 10)
                        .source()
                        .errorCode()
                        .orElseThrow());
        assertEquals(
                "READ_FAILED",
                reader.read(InstructionScope.PROJECT, temporaryDirectory.resolve("missing.md"), "missing.md", 10)
                        .source()
                        .errorCode()
                        .orElseThrow());

        Path link = temporaryDirectory.resolve("instruction-link.md");
        Files.createSymbolicLink(link, regular);
        assertEquals(
                "SYMLINK_REJECTED",
                reader.read(InstructionScope.PROJECT, link, "instruction-link.md", 10)
                        .source()
                        .errorCode()
                        .orElseThrow());
        InstructionFileReader.ReadResult zero = reader.read(InstructionScope.PROJECT, regular, "instruction.md", 0);
        assertTrue(zero.source().truncated());
        assertEquals(0, zero.source().includedBytes());
        assertEquals("", zero.content());
    }
}
