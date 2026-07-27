package com.javaclaw.task.sdd;

import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.config.FileDatabaseAccess;
import com.javaclaw.task.sdd.gate.AutoApproveReviewGate;
import com.javaclaw.task.sdd.spec.Capability;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.spec.SpecParser;
import com.javaclaw.task.sdd.spec.SpecStore;
import com.javaclaw.task.sdd.spec.TaskItem;
import com.javaclaw.task.sdd.verify.ScenarioVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SddOrchestratorStageTest {
    @TempDir Path temp;

    @Test
    void 准备阶段落盘后实现阶段从OpenSpec继续且不重复准备() throws Exception {
        Path workDir = temp.resolve("workspace");
        Files.createDirectories(workDir);
        DatabaseAccess database = new FileDatabaseAccess(temp.resolve("database"));
        String workspaceId = "stage-workspace";
        String id = "stage-test-" + System.nanoTime();
        TaskContext context = new TaskContext(id, "阶段恢复", "验证真实阶段边界",
                workDir.toString(), "system");
        SpecStore store = new SpecStore(workDir.toString(), database, workspaceId);
        AtomicInteger prepareCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        SddAgents agents = new SddAgents() {
            @Override public Proposal clarifyAndPropose(TaskContext ctx, String feedback) {
                prepareCalls.incrementAndGet();
                return new Proposal("需要阶段恢复", "拆分准备和实现", "");
            }
            @Override public List<Capability> specify(TaskContext ctx, Proposal proposal) {
                return List.of();
            }
            @Override public String design(TaskContext ctx, Proposal proposal, List<Capability> capabilities) {
                return null;
            }
            @Override
            public List<TaskItem> planTasks(TaskContext ctx, Proposal proposal,
                                            List<Capability> capabilities, String design, String feedback) {
                return List.of(new TaskItem(1, "完成实现", List.of(), "完成", false));
            }
            @Override
            public ExecutionResult executeTask(TaskContext ctx, TaskItem current,
                                               List<TaskItem> doneItems, List<Capability> specs) {
                executeCalls.incrementAndGet();
                return ExecutionResult.done("完成");
            }
            @Override
            public List<String> remediate(TaskContext ctx, List<Scenario> failedScenarios,
                                          OpenSpecChange change) {
                return List.of();
            }
        };
        SddOrchestrator orchestrator = new SddOrchestrator(context, store,
                new ScenarioVerifier(workDir.toString(), null, null), agents,
                new AutoApproveReviewGate(), SddProgress.NOOP, database, workspaceId)
                .completionStamp("test");

        assertTrue(SddTaskRunner.requiresPreparation(true,
                store.readChange(context.slug(), context.id(), context.title())));
        assertNull(orchestrator.prepare());
        assertEquals(1, prepareCalls.get());
        assertEquals(0, executeCalls.get());
        String storedTasks = readTasksDocument(database, workspaceId, workDir, context.slug());
        assertNotNull(storedTasks);
        assertEquals(1, SpecParser.parseTasks(storedTasks).size());
        OpenSpecChange prepared = store.readChange(context.slug(), context.id(), context.title());
        assertEquals(1, prepared.tasks().size());
        assertFalse(SddTaskRunner.requiresPreparation(true, prepared));
        assertTrue(SddTaskRunner.requiresPreparation(false, prepared));

        SddOutcome outcome = orchestrator.implementPrepared();
        assertTrue(outcome.isCompleted());
        assertEquals(1, prepareCalls.get(), "实现阶段不得重新执行提案/规格/计划");
        assertEquals(1, executeCalls.get());
        assertEquals(1, countVerifyCache(database, workspaceId, workDir, context.slug()),
                "验收缓存必须写入与 OpenSpec 相同的注入数据库");
    }

    private static String readTasksDocument(DatabaseAccess database, String workspaceId,
                                            Path workDir, String slug) throws Exception {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT doc_text
                     FROM sdd_spec_docs
                     WHERE workspace_id = ? AND work_dir = ? AND slug = ? AND doc_path = 'tasks.md'
                     """)) {
            statement.setString(1, workspaceId);
            statement.setString(2, workDir.toAbsolutePath().normalize().toString());
            statement.setString(3, slug);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static int countVerifyCache(DatabaseAccess database, String workspaceId,
                                        Path workDir, String slug) throws Exception {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM sdd_verify_cache
                     WHERE workspace_id = ? AND work_dir = ? AND slug = ?
                     """)) {
            statement.setString(1, workspaceId);
            statement.setString(2, workDir.toAbsolutePath().normalize().toString());
            statement.setString(3, slug);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }
}
