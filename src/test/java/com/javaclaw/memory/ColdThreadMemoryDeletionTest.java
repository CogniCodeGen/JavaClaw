package com.javaclaw.memory;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.ThreadClient;
import com.javaclaw.framework.api.ThreadStartRequest;
import com.javaclaw.memory.embed.TestEmbeddingGatewayFactory;
import com.javaclaw.memory.model.CorrectionRecord;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ColdThreadMemoryDeletionTest {
    @TempDir Path temporary;

    @Test void rootThreadDeleteCleansColdGraphAndLegacyButPreservesHabitsAndOtherGraphs() {
        try (var context = ApplicationContexts.createRoot(new DataRoot(temporary.resolve("root")))) {
            var threads = context.getBean(ThreadClient.class);
            var settings = context.getBean(AgentConfig.class);
            Path global = context.getBean(WorkspaceManager.class).getGlobalDataPath();
            Path memoryRoot = global.resolve("memory-stores/cold-workspace");
            RunScope source = new RunScope("cold-workspace", "local-user", "removed");
            threads.start(ThreadStartRequest.root(source, "cold source"));
            MemoryGraphScope graph = MemoryGraphScope.thread(source);
            var survivor = new MemoryGraphScope("cold-workspace", "local-user", "fork-survivor", MemoryGraphScope.Kind.THREAD);
            try (var fixture = fixture(memoryRoot, settings, false)) {
                Episode original = new Episode("removed", "不应从旧混库重新读取的问题", "旧的回答");
                fixture.memory.store().addPendingEpisode(original, "test");
                Fact fact = new Fact("旧会话", "旧的来源事实", null);
                fact.source = original;
                fixture.memory.store().addPendingFact(fact, "test");
                CorrectionRecord correction = new CorrectionRecord();
                correction.targetFactId = fact.id;
                correction.sourceInput = "旧的来源纠错原文";
                fixture.memory.store().addCorrection(correction, "test");
            }
            try (var fixture = fixture(memoryRoot, settings, true)) {
                fixture.memory.rememberTurn(graph, null, "old-turn", 2, "冷会话问题", "冷会话答案", null, false);
                fixture.memory.rememberTurn(survivor, null, "fork-turn", 2, "独立分支的问题", "独立分支的答案", null, false);
                fixture.memory.rememberExplicitPreference(graph, "old-turn", "我喜欢简洁的中文回答");
            }
            assertTrue(Files.isDirectory(graph.directory(memoryRoot)));
            RunScope otherUser = new RunScope("cold-workspace", "other-user", "removed");
            threads.start(ThreadStartRequest.root(otherUser, "other user same thread id"));
            threads.delete(otherUser);
            try (var fixture = fixture(memoryRoot, settings, true)) {
                var legacy = fixture.memory.inScope(new MemoryGraphScope("cold-workspace", "local-user", "", MemoryGraphScope.Kind.LEGACY));
                assertEquals(1, legacy.episodes().size(), "另一个用户删除同名会话不能隐藏桌面旧混库");
                assertEquals(1, legacy.facts().size());
                assertFalse(fixture.memory.facts().getFirst().evidenceDeleted);
            }
            try (var fixture = fixture(memoryRoot, settings, true, "other-user")) {
                assertTrue(fixture.memory.scopes().stream().noneMatch(scope -> scope.kind() == MemoryGraphScope.Kind.LEGACY));
                assertThrows(IllegalArgumentException.class, () -> fixture.memory.inScope(
                        new MemoryGraphScope("cold-workspace", "other-user", "", MemoryGraphScope.Kind.LEGACY)));
                assertEquals(0, fixture.memory.migrateLegacy(context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class), id -> true));
            }
            // Only the root Spring runtime exists: no workspace MemoryService listener is registered.
            threads.delete(source);
            threads.delete(source); // Replay after an interrupted deletion is idempotent.
            assertFalse(Files.exists(graph.directory(memoryRoot)));
            assertTrue(Files.isDirectory(survivor.directory(memoryRoot)));
            try (var fixture = fixture(memoryRoot, settings, true)) {
                assertThrows(IllegalStateException.class, () -> fixture.memory.inScope(graph));
                assertEquals(1, fixture.memory.facts().size());
                assertTrue(fixture.memory.facts().getFirst().evidenceDeleted);
                assertEquals("我喜欢简洁的中文回答", fixture.memory.facts().getFirst().text);
                assertTrue(fixture.memory.recall(survivor, "独立分支", 8).contains("独立分支的答案"));
                var legacy = fixture.memory.inScope(new MemoryGraphScope("cold-workspace", "local-user", "", MemoryGraphScope.Kind.LEGACY));
                assertTrue(legacy.episodes().isEmpty());
                assertTrue(legacy.facts().isEmpty());
                assertTrue(legacy.corrections().isEmpty());
                assertFalse(legacy.recentChangeLog(100).stream().anyMatch(change -> change.detail.contains("旧的来源")));
            }
        }
    }

    @Test void cleanupReusesOpenRegistryLeaseAndRejectsWorkspacePathEscape() {
        try (var context = ApplicationContexts.createRoot(new DataRoot(temporary.resolve("active")))) {
            var settings = context.getBean(AgentConfig.class);
            Path global = context.getBean(WorkspaceManager.class).getGlobalDataPath();
            Path memoryRoot = global.resolve("memory-stores/cold-workspace");
            var graph = new MemoryGraphScope("cold-workspace", "local-user", "active-source", MemoryGraphScope.Kind.THREAD);
            try (var fixture = fixture(memoryRoot, settings, true)) {
                fixture.memory.rememberExplicitPreference(graph, "turn", "我偏好表格展示方案");
                var stale = fixture.memory.inScope(graph).store();
                var cleanup = new ThreadMemoryCleanup(global);
                cleanup.delete(new RunScope("cold-workspace", "local-user", "active-source"));
                assertTrue(fixture.memory.facts().getFirst().evidenceDeleted);
                assertThrows(IllegalStateException.class, () -> stale.addPendingEpisode(new Episode("active-source", "late", "late"), "late"));
                fixture.memory.deleteThread(graph); // Active workspace listener may still run afterwards.
                assertEquals(1, fixture.memory.facts().size());
                assertFalse(Files.exists(graph.directory(memoryRoot)));
                assertThrows(IllegalArgumentException.class,
                        () -> cleanup.delete(new RunScope("../outside", "local-user", "escape")));
                assertFalse(Files.exists(global.resolve("outside")));
            }
        }
    }

    private Fixture fixture(Path path, AgentConfig settings, boolean scoped) {
        return fixture(path, settings, scoped, "local-user");
    }
    private Fixture fixture(Path path, AgentConfig settings, boolean scoped, String user) {
        var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
        var memory = new MemoryService(request -> { throw new AssertionError("deletion must never call a model"); },
                embedding.gateway(), embedding.tasks(), settings);
        if (scoped) memory.open(path, "cold-workspace", user); else memory.open(path);
        return new Fixture(memory, embedding);
    }
    private record Fixture(MemoryService memory, TestEmbeddingGatewayFactory.Fixture embedding) implements AutoCloseable {
        @Override public void close() { memory.close(); embedding.close(); }
    }
}
