package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.server.persistence.CodingOperationRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingPlatformIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    private CodingTestFixture fixture;

    @BeforeEach
    void initializeRealPlatformWithoutModel() throws Exception {
        fixture = new CodingTestFixture(temporaryDirectory);
    }

    @AfterEach
    void closeAllTurnResources() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void 中文项目读取搜索条件修改并保存真实文件事实() throws Exception {
        Files.writeString(fixture.root.resolve("Example.java"), "class Example { // 原始实现\n}\n");
        var listed = fixture.invoke("file_list", new CodingContracts.FileList(".", Optional.empty(), 10), "list");
        assertTrue(listed.success(), listed.response().payload().json());
        assertEquals(
                1,
                fixture.json
                        .decode(listed.response().payload(), CodingResults.FileListResult.class)
                        .entries()
                        .size());
        var read = fixture.invoke("file_read", new CodingContracts.FileRead("Example.java", 0, 1024), "read");
        var original = fixture.json.decode(read.response().payload(), CodingResults.FileReadResult.class);
        assertFalse(original.binary());
        var found = fixture.invoke(
                "file_search", new CodingContracts.FileSearch(".", "原始", "*.java", true, 10, 4096), "search");
        assertEquals(
                1,
                fixture.json
                        .decode(found.response().payload(), CodingResults.FileSearchResult.class)
                        .matches()
                        .size());
        var patch = new CodingContracts.ApplyPatch(List.of(new CodingContracts.FileEdit(
                "Example.java",
                Optional.of(original.sha256()),
                Optional.of("class Example { // 已修复\n}\n"),
                Optional.empty())));
        var changed = fixture.invoke("file_apply_patch", patch, "patch");
        assertTrue(changed.success());
        assertEquals(1, changed.facts().size());
        assertTrue(Files.readString(fixture.root.resolve("Example.java")).contains("已修复"));
        var result = fixture.json.decode(changed.response().payload(), CodingResults.PatchResult.class);
        assertTrue(result.complete());
        assertEquals(changed, fixture.invoke("file_apply_patch", patch, "patch"));
    }

    @Test
    void 摘要冲突预检保护用户未提交修改且不改其他目标() throws Exception {
        Files.writeString(fixture.root.resolve("user.txt"), "用户已编辑");
        var patch = new CodingContracts.ApplyPatch(List.of(
                new CodingContracts.FileEdit("new.txt", Optional.empty(), Optional.of("不能提前写入"), Optional.empty()),
                new CodingContracts.FileEdit(
                        "user.txt", Optional.of("0".repeat(64)), Optional.of("错误覆盖"), Optional.empty())));
        var result = fixture.invoke("file_apply_patch", patch, "conflict");
        assertFalse(result.success());
        assertTrue(result.facts().isEmpty());
        assertFalse(Files.exists(fixture.root.resolve("new.txt")));
        assertEquals("用户已编辑", Files.readString(fixture.root.resolve("user.txt")));
        assertEquals(
                "FILE_DIGEST_CONFLICT",
                fixture.json
                        .decode(result.response().payload(), CodingResults.Failure.class)
                        .errorCode());
    }

    @Test
    void 创建移动删除保留逐文件回执和原文备份() throws Exception {
        var create = new CodingContracts.ApplyPatch(List.of(
                new CodingContracts.FileEdit("a.txt", Optional.empty(), Optional.of("完整内容"), Optional.empty())));
        assertTrue(fixture.invoke("file_apply_patch", create, "create").success());
        String digest = digest("完整内容");
        var move = new CodingContracts.ApplyPatch(List.of(
                new CodingContracts.FileEdit("a.txt", Optional.of(digest), Optional.empty(), Optional.of("b.txt"))));
        var moved = fixture.invoke("file_apply_patch", move, "move");
        assertTrue(moved.success());
        assertFalse(Files.exists(fixture.root.resolve("a.txt")));
        assertEquals("完整内容", Files.readString(fixture.root.resolve("b.txt")));
        var delete = new CodingContracts.ApplyPatch(List.of(
                new CodingContracts.FileEdit("b.txt", Optional.of(digest), Optional.empty(), Optional.empty())));
        assertTrue(fixture.invoke("file_apply_patch", delete, "delete").success());
        assertFalse(Files.exists(fixture.root.resolve("b.txt")));
    }

    @Test
    void 固定调用身份不允许同键替换参数并且能力不可重复使用() throws Exception {
        Files.writeString(fixture.root.resolve("one.txt"), "一");
        var first = new CodingContracts.FileRead("one.txt", 0, 20);
        var request = fixture.request(fixture.turn, "file_read", first, "single");
        try (var binding = fixture.platform.bindTool(request, fixture.permission, new CancellationSource())) {
            binding.invoke();
            assertThrows(SecurityException.class, binding::invoke);
        }
        assertThrows(
                RuntimeException.class,
                () -> fixture.invoke("file_read", new CodingContracts.FileRead("different.txt", 0, 20), "single"));
    }

    @Test
    void 管理请求不能冒充工具且跨Turn终端读取失败() throws Exception {
        var request = new ExtensionRequest(
                fixture.workspace.id(),
                Optional.of(fixture.turn.threadId()),
                Optional.of(fixture.turn.id()),
                "command_run",
                fixture.json.encode(new CodingContracts.CommandRun(List.of("java", "--version"), ".", 10, 4096)),
                Optional.of("manage-execute"),
                0,
                Optional.empty());
        try (var binding =
                fixture.platform.bindManagement(request, ContributionKind.COMMAND, new CancellationSource())) {
            assertThrows(SecurityException.class, binding::invoke);
        }
        var missing =
                fixture.invoke("terminal_read", new CodingContracts.TerminalRead("other-turn", 0, 10, 0), "cross-turn");
        assertFalse(missing.success());
    }

    @Test
    void 原生命令退出码决定成功而非进程启动() throws Exception {
        var succeeded = fixture.invoke(
                "command_run", new CodingContracts.CommandRun(List.of("java", "--version"), ".", 15, 8192), "version");
        assertTrue(succeeded.success(), succeeded.response().payload().json());
        var success = fixture.json.decode(succeeded.response().payload(), CodingResults.CommandResult.class);
        assertEquals(Optional.of(0), success.command().exitCode());
        var failed = fixture.invoke(
                "command_run",
                new CodingContracts.CommandRun(List.of("java", "--bad-option-coding-test"), ".", 15, 8192),
                "bad-option");
        assertFalse(failed.success());
        assertEquals(1, failed.facts().size());
        var failure = fixture.json.decode(failed.response().payload(), CodingResults.CommandResult.class);
        assertEquals(CodingResults.ProcessState.FAILED, failure.command().state());
        assertTrue(failure.command().exitCode().orElseThrow() != 0);
        var ledger = new CodingOperationRepository(fixture.database, fixture.json, fixture.clock);
        assertFalse(ledger.find(fixture.workspace.id(), failure.command().operationId())
                .orElseThrow()
                .success());
    }

    @Test
    void 未安装工具链和未授权仓库明确失败且不执行项目代码() throws Exception {
        var command = fixture.invoke(
                "command_run",
                new CodingContracts.CommandRun(List.of("node", "--version"), ".", 10, 1024),
                "missing-node");
        assertFalse(command.success());
        assertTrue(command.facts().isEmpty());
        var preparation = fixture.invoke(
                "dependencies_prepare",
                new CodingContracts.DependenciesPrepare(CodingContracts.PackageManager.NPM, ".", List.of()),
                "missing-repositories");
        assertFalse(preparation.success());
        assertTrue(preparation.facts().isEmpty());
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
