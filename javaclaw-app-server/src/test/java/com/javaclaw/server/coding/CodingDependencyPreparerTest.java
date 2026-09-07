package com.javaclaw.server.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingDependencyPreparerTest {
    @TempDir
    Path directory;

    @Test
    void 完整准备从无锁选择安装并记录源文件变化及真实输出后关闭网络和工具链租约() throws Exception {
        try (var fixture = new CodingPreparationFixture(directory)) {
            Files.writeString(fixture.base.root.resolve("package.json"), "{\"name\":\"test\"}");
            Files.writeString(fixture.base.root.resolve("source.js"), "before");
            var invocation = npm(fixture, "install");
            fixture.sandbox.step = command -> {
                assertTrue(command.argv().contains("install"));
                assertTrue(command.argv().contains("--ignore-scripts"));
                assertEquals("ACTIVE", fixture.grantState(invocation.id()));
                assertEquals(
                        "STARTED",
                        fixture.operations
                                .find(fixture.base.workspace.id(), invocation.id())
                                .orElseThrow()
                                .state());
                Files.writeString(fixture.base.root.resolve("package-lock.json"), "{\"lockfileVersion\":3}");
                Files.writeString(fixture.base.root.resolve("source.js"), "after");
            };
            var result = fixture.preparer.prepare(invocation);
            assertTrue(result.success());
            var evidence = fixture.evidence(invocation);
            assertTrue(evidence.after().isPresent());
            assertEquals(
                    Set.of("package-lock.json", "source.js"),
                    evidence.observedChanges().stream()
                            .map(change -> change.path())
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(evidence.artifacts().stream()
                    .anyMatch(artifact -> artifact.source().equals("package-lock.json")
                            && artifact.kind().equals("lock")
                            && artifact.phase().equals("after")));
            assertTrue(evidence.artifacts().stream()
                    .anyMatch(artifact -> artifact.source().equals("stdout")
                            && artifact.phase().equals("execution")));
            assertEquals(
                    2,
                    result.facts().stream()
                            .filter(fact -> fact.payload() instanceof CorePayloads.FileChange)
                            .count());
            assertClosed(fixture, invocation.id());
        }
    }

    @Test
    void 已有锁选择ci并保留非零退出前产生的锁和源文件真实变更() throws Exception {
        try (var fixture = new CodingPreparationFixture(directory)) {
            Files.writeString(fixture.base.root.resolve("package.json"), "{}");
            Files.writeString(fixture.base.root.resolve("package-lock.json"), "original lock");
            var invocation = npm(fixture, "ci-failure");
            fixture.sandbox.exitCode = 17;
            fixture.sandbox.step = command -> {
                assertTrue(command.argv().contains("ci"));
                assertFalse(command.argv().contains("install"));
                Files.writeString(fixture.base.root.resolve("package-lock.json"), "changed before failure");
                Files.writeString(fixture.base.root.resolve("generated.js"), "partial output");
            };
            var result = fixture.preparer.prepare(invocation);
            assertFalse(result.success());
            var preparation = (CodingResults.PreparationResult) result.value();
            assertEquals(
                    17, preparation.command().command().exitCode().orElseThrow().intValue());
            assertEquals("changed before failure", Files.readString(fixture.base.root.resolve("package-lock.json")));
            assertEquals("partial output", Files.readString(fixture.base.root.resolve("generated.js")));
            assertEquals(2, fixture.evidence(invocation).observedChanges().size());
            assertClosed(fixture, invocation.id());
        }
    }

    @Test
    void 启动后异常仍捕获后快照并保持未知启动意图且释放全部租约() throws Exception {
        try (var fixture = new CodingPreparationFixture(directory)) {
            Files.writeString(fixture.base.root.resolve("package.json"), "{}");
            var invocation = npm(fixture, "unknown");
            fixture.sandbox.step = command -> {
                Files.writeString(fixture.base.root.resolve("changed.js"), "external effect");
                throw new IOException("lost process result after spawn");
            };
            var failure = assertThrows(IOException.class, () -> fixture.preparer.prepare(invocation));
            assertEquals("lost process result after spawn", failure.getMessage());
            var evidence = fixture.evidence(invocation);
            assertTrue(evidence.after().isPresent());
            assertFalse(evidence.complete());
            assertTrue(evidence.after().orElseThrow().omissions().stream().anyMatch(value -> value.contains("不猜测")));
            assertEquals("changed.js", evidence.observedChanges().getFirst().path());
            assertEquals(
                    "STARTED",
                    fixture.operations
                            .find(fixture.base.workspace.id(), invocation.id())
                            .orElseThrow()
                            .state());
            assertClosed(fixture, invocation.id());
        }
    }

    @Test
    void 仓库缺少当前权限时在观察授权和进程启动前拒绝() throws Exception {
        try (var fixture = new CodingPreparationFixture(directory)) {
            var invocation = fixture.invocation(
                    "denied-host", CodingContracts.PackageManager.NPM, false, Set.of("not-authorized.example"));
            assertThrows(SecurityException.class, () -> fixture.preparer.prepare(invocation));
            assertEquals("ABSENT", fixture.grantState(invocation.id()));
            assertEquals(0, fixture.sandbox.calls);
            assertEquals(0, fixture.toolchains.acquired.get());
        }
    }

    @Test
    void 未授权生命周期脚本时Maven和Pip计划拒绝且已打开的授权被撤销() throws Exception {
        try (var fixture = new CodingPreparationFixture(directory)) {
            for (var manager : List.of(CodingContracts.PackageManager.MAVEN, CodingContracts.PackageManager.PIP)) {
                var invocation = fixture.invocation("scripts-" + manager, manager, false, Set.of("repo.example"));
                assertThrows(SecurityException.class, () -> fixture.preparer.prepare(invocation));
                assertEquals("REVOKED", fixture.grantState(invocation.id()));
                assertTrue(fixture.evidence(invocation).after().isEmpty());
                assertEquals(
                        "PREPARED",
                        fixture.operations
                                .find(fixture.base.workspace.id(), invocation.id())
                                .orElseThrow()
                                .state());
            }
            assertEquals(0, fixture.sandbox.calls);
            assertEquals(0, fixture.toolchains.acquired.get());
        }
    }

    private static CodingInvocation npm(CodingPreparationFixture fixture, String id) {
        return fixture.invocation(id, CodingContracts.PackageManager.NPM, false, Set.of("repo.example"));
    }

    private static void assertClosed(CodingPreparationFixture fixture, String id) throws Exception {
        assertEquals(1, fixture.sandbox.calls);
        assertEquals(1, fixture.toolchains.acquired.get());
        assertEquals(1, fixture.toolchains.released.get());
        assertEquals("REVOKED", fixture.grantState(id));
        assertThrows(IOException.class, () -> CodingPreparationFixture.connect(fixture.sandbox.endpoint));
    }
}
