package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodingContractsTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void 执行目录限制条数和重复身份且允许未来状态独立演进() {
        var summary = new CodingResults.ExecutionSummary(
                "run-1", "command_run", com.javaclaw.api.TurnId.random(), "FUTURE_STATE", 0, Optional.empty());
        assertEquals(List.of(summary), new CodingResults.ExecutionList(List.of(summary)).executions());
        assertThrows(IllegalArgumentException.class, () -> new CodingResults.ExecutionList(List.of(summary, summary)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodingResults.ExecutionList(java.util.Collections.nCopies(101, summary)));
    }

    @Test
    void 分页路径和字节预算不能逃逸或无限增长() {
        assertEquals("src/Main.java", new CodingContracts.FileRead("src\\Main.java", 10, 512).path());
        for (String path : List.of("/etc/passwd", "../secret", "src/../secret", "C:\\secret", "src//Main.java")) {
            assertThrows(IllegalArgumentException.class, () -> new CodingContracts.FileRead(path, 0, 100));
        }
        assertThrows(IllegalArgumentException.class, () -> new CodingContracts.FileRead("a", -1, 100));
        assertThrows(IllegalArgumentException.class, () -> new CodingContracts.FileRead("a", 0, 1_048_577));
        assertThrows(IllegalArgumentException.class, () -> new CodingContracts.FileList(".", Optional.empty(), 0));
    }

    @Test
    void 删除移动与新建有明确的摘要前提且补丁不重叠() {
        var create = new CodingContracts.FileEdit("new.txt", Optional.empty(), Optional.of(""), Optional.empty());
        assertEquals(Optional.of(""), create.content());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodingContracts.FileEdit("old.txt", Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodingContracts.FileEdit(
                        "old.txt", Optional.empty(), Optional.empty(), Optional.of("new.txt")));
        var move =
                new CodingContracts.FileEdit("old.txt", Optional.of(DIGEST), Optional.empty(), Optional.of("new.txt"));
        assertThrows(IllegalArgumentException.class, () -> new CodingContracts.ApplyPatch(List.of(create, move)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodingContracts.FileEdit(
                        "old.txt", Optional.of("abc"), Optional.of("content"), Optional.empty()));
    }

    @Test
    void 命令保留参数而拒绝空白值且原生依赖目标仅用于Java管理器() {
        var command = new CodingContracts.CommandRun(List.of("java", "-version"), ".", 10, 1024);
        assertEquals(List.of("java", "-version"), command.argv());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodingContracts.CommandRun(List.of("java", ""), ".", 10, 1024));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodingContracts.DependenciesPrepare(
                        CodingContracts.PackageManager.NPM, ".", List.of("install")));
        assertEquals(
                List.of("test"),
                new CodingContracts.DependenciesPrepare(CodingContracts.PackageManager.MAVEN, ".", List.of("test"))
                        .targets());
    }

    @Test
    void 工具链只能来自完整摘要的HTTPS发行清单而仓库配置不接受URL() {
        var ref =
                new CodingEnvironmentContracts.ToolchainRef(CodingEnvironmentContracts.ToolchainKind.JDK, "25", DIGEST);
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodingEnvironmentContracts.ToolchainArtifact(
                        ref,
                        "linux",
                        "x86_64",
                        URI.create("http://example.com/jdk.zip"),
                        "zip",
                        Map.of("java", "bin/java"),
                        100,
                        "GPL"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodingEnvironmentContracts.EnvironmentSpec(
                        "dev", List.of(ref), Set.of("https://registry.npmjs.org"), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodingEnvironmentContracts.EnvironmentSpec(
                        "dev", List.of(ref, ref), Set.of("repo.maven.apache.org"), true));
    }
}
