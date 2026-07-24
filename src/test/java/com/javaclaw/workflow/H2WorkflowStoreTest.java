package com.javaclaw.workflow;

import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.node.PublicNodeCatalog;
import com.javaclaw.workflow.runtime.GraphEvent;
import com.javaclaw.workflow.runtime.GraphExecutionManager;
import com.javaclaw.workflow.service.SystemGraphFactory;
import com.javaclaw.workflow.store.H2GraphCheckpointStore;
import com.javaclaw.workflow.store.H2WorkflowDefinitionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class H2WorkflowStoreTest {
    @TempDir Path temp;

    @Test void 草稿发布与运行快照可从H2往返() throws Exception {
        String old = System.getProperty("user.dir");
        System.setProperty("user.dir", temp.toString());
        try {
            String workspace = "ws-test";
            var registry = PublicNodeCatalog.createRegistry();
            var definitions = new H2WorkflowDefinitionStore(workspace);
            var draftEditor = new WorkflowEditorModel(WorkflowEditorModel.blank("持久化测试"));
            var draft = draftEditor.current();
            var saved = definitions.saveDraft(draft);
            assertEquals(1, saved.draftRevision());
            var published = definitions.publish(draft.id(), registry);
            assertTrue(published.isPublished());
            assertEquals(draft.id(), definitions.get(draft.id()).published().id());
            assertNull(new H2WorkflowDefinitionStore("ws-other").get(draft.id()));

            var checkpoints = new H2GraphCheckpointStore(workspace);
            try (var executions = new GraphExecutionManager(registry, checkpoints)) {
                CountDownLatch done = new CountDownLatch(1);
                var run = executions.start(published.published(), "thread", new com.javaclaw.workflow.model.GraphState(),
                        event -> { if (event instanceof GraphEvent.RunFinished) done.countDown(); }, Map.of());
                assertTrue(done.await(3, TimeUnit.SECONDS));
                var loaded = checkpoints.loadRun(run.id());
                assertNotNull(loaded);
                assertEquals(RunStatus.COMPLETED, loaded.status());
                assertEquals(published.publishedVersion(), loaded.workflowVersion());

                GraphDefinition edited = new GraphDefinition(draft.schemaVersion(), draft.id(), "第二版",
                        draft.description(), draft.version(), draft.kind(), draft.startNodeId(),
                        draft.nodes(), draft.edges(), draft.maxSteps());
                definitions.saveDraft(edited);
                var version2 = definitions.publish(draft.id(), registry);
                assertEquals(2, version2.publishedVersion());
                assertEquals(1, loaded.definition().version());
                assertEquals("持久化测试", loaded.definition().name());
            }

            checkpoints.saveThreadState(draft.id(), "same-thread", new GraphState().apply(
                    StatePatch.builder().set("owner", "a").build()));
            var otherWorkspace = new H2GraphCheckpointStore("ws-other");
            otherWorkspace.saveThreadState(draft.id(), "same-thread", new GraphState().apply(
                    StatePatch.builder().set("owner", "b").build()));
            assertEquals("a", checkpoints.loadThreadState(draft.id(), "same-thread").get("owner").asText());
            assertEquals("b", otherWorkspace.loadThreadState(draft.id(), "same-thread").get("owner").asText());

            long now = System.currentTimeMillis();
            var recovery = new com.javaclaw.workflow.runtime.GraphRun(
                    "recovery-run", published.published().id(), published.published().version(),
                    "recovery-thread", published.published(), new GraphState(),
                    RunStatus.RECOVERY_REQUIRED, "start", null, 0, 0,
                    null, null, null, now, now);
            checkpoints.createRun(recovery);
            assertEquals(recovery.id(),
                    checkpoints.findRecoverableRun(draft.id(), "recovery-thread").id());
            assertNull(otherWorkspace.findRecoverableRun(draft.id(), "recovery-thread"));

            var createdBeforeCrash = new com.javaclaw.workflow.runtime.GraphRun(
                    published.published(), "created-before-crash", new GraphState());
            checkpoints.createRun(createdBeforeCrash);
            try (var startup = new GraphExecutionManager(registry, checkpoints)) {
                assertEquals(RunStatus.RECOVERY_REQUIRED,
                        checkpoints.loadRun(createdBeforeCrash.id()).status());
            }

            var copiedSystem = definitions.cloneFrom(SystemGraphFactory.pipeline(
                    "system-sample", "系统样例", "只读系统图", "执行"), "可编辑副本");
            assertEquals(com.javaclaw.workflow.model.GraphKind.CUSTOM, copiedSystem.draft().kind());
            assertEquals("可编辑副本", copiedSystem.name());
            assertNotEquals("system-sample", copiedSystem.id());
            assertTrue(definitions.validate(copiedSystem.draft(), registry).isEmpty(),
                    "系统图副本应转换为可直接发布的公共节点模板");
            assertTrue(definitions.publish(copiedSystem.id(), registry).isPublished());
        } finally {
            System.setProperty("user.dir", old);
        }
    }
}
