package com.javaclaw.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.FileDatabaseAccess;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.node.BasicNodeExecutors;
import com.javaclaw.workflow.runtime.*;
import com.javaclaw.workflow.service.SystemGraphFactory;
import com.javaclaw.workflow.store.H2GraphCheckpointStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class GraphDriverDeletionTest {
    @TempDir Path directory;

    @Test void deletionInterruptsAndDrainsTheDriverBeforePurgingItsHistory() throws Exception {
        var store = new H2GraphCheckpointStore("workspace", new FileDatabaseAccess(directory), new ObjectMapper());
        var registry = new NodeExecutorRegistry(); BasicNodeExecutors.register(registry);
        var entered = new CountDownLatch(1); var exited = new AtomicBoolean();
        registry.register(new NodeExecutor() {
            @Override public String type() { return "system.pipeline"; }
            @Override public NodeResult execute(NodeExecutionContext context) throws Exception {
                entered.countDown();
                try { new CountDownLatch(1).await(); return NodeResult.next(); }
                finally { exited.set(true); }
            }
        });
        var graph = SystemGraphFactory.pipeline("delete-driver", "delete", "", "wait");
        try (var tasks = new ManagedTaskExecutor(); var manager = new GraphExecutionManager(registry, store, tasks)) {
            var run = manager.start(graph, "coordinator", new GraphState(), GraphListener.NOOP,
                    WorkflowExecutionServices.EMPTY);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            manager.deleteThread("coordinator");
            assertTrue(exited.get());
            assertFalse(manager.isActive(run.id()));
            assertNull(store.loadRun(run.id()));
            assertTrue(store.listRuns(null, 10).isEmpty());
            assertThrows(IllegalStateException.class, () -> manager.start(graph, "coordinator", new GraphState(),
                    GraphListener.NOOP, WorkflowExecutionServices.EMPTY));
            assertThrows(IllegalArgumentException.class, () -> manager.resume(run.id(), null, true,
                    GraphListener.NOOP, WorkflowExecutionServices.EMPTY));
            manager.deleteThread("coordinator");
        }
    }

    @Test void deletionWaitsForSubmissionToPublishTheHandleAndForActualDriverExit() throws Exception {
        var store = new H2GraphCheckpointStore("workspace", new FileDatabaseAccess(directory), new ObjectMapper());
        var registry = new NodeExecutorRegistry(); BasicNodeExecutors.register(registry);
        var entered = new CountDownLatch(1); var publishHandle = new CountDownLatch(1);
        var exitDriver = new CountDownLatch(1); var exited = new AtomicBoolean();
        registry.register(new NodeExecutor() {
            @Override public String type() { return "system.pipeline"; }
            @Override public NodeResult execute(NodeExecutionContext context) {
                entered.countDown();
                boolean interrupted = false;
                while (exitDriver.getCount() > 0) {
                    try { exitDriver.await(); }
                    catch (InterruptedException cancelled) { interrupted = true; }
                }
                exited.set(true);
                if (interrupted) Thread.currentThread().interrupt();
                return NodeResult.next();
            }
        });
        var graph = SystemGraphFactory.pipeline("publication-race", "delete", "", "wait");
        try (var tasks = new ManagedTaskExecutor()) {
            com.javaclaw.platform.execution.TaskSubmitter delayed = new com.javaclaw.platform.execution.TaskSubmitter() {
                @Override public <T> com.javaclaw.platform.execution.TaskHandle<T> submit(
                        com.javaclaw.platform.execution.TaskSpec spec, com.javaclaw.platform.execution.ManagedTask<T> task) {
                    var handle = tasks.submit(spec, task);
                    try { publishHandle.await(); }
                    catch (InterruptedException interrupted) {
                        handle.cancel(); Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                    }
                    return handle;
                }
            };
            try (var manager = new GraphExecutionManager(registry, store, delayed)) {
                var starting = CompletableFuture.supplyAsync(() -> manager.start(graph, "coordinator", new GraphState(),
                        GraphListener.NOOP, WorkflowExecutionServices.EMPTY));
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    var deleting = CompletableFuture.runAsync(() -> manager.deleteThread("coordinator"));
                    assertThrows(TimeoutException.class, () -> deleting.get(100, TimeUnit.MILLISECONDS));
                    assertFalse(store.listRuns(null, 10).isEmpty(), "history must remain until its driver exits");
                    publishHandle.countDown();
                    var run = starting.get(3, TimeUnit.SECONDS);
                    assertThrows(TimeoutException.class, () -> deleting.get(100, TimeUnit.MILLISECONDS));
                    assertFalse(exited.get());
                    exitDriver.countDown();
                    deleting.get(3, TimeUnit.SECONDS);
                    assertTrue(exited.get());
                    assertFalse(manager.isActive(run.id()));
                    assertNull(store.loadRun(run.id()));
                } finally { publishHandle.countDown(); exitDriver.countDown(); }
            }
        }
    }

    @Test void cancellationBeforeEnteringTheGraphClosureReleasesTheReservedActivitySlot() throws Exception {
        var store = new H2GraphCheckpointStore("workspace", new FileDatabaseAccess(directory), new ObjectMapper());
        var registry = new NodeExecutorRegistry(); BasicNodeExecutors.register(registry);
        var waiting = new CountDownLatch(1);
        try (var tasks = new ManagedTaskExecutor()) {
            com.javaclaw.platform.execution.TaskSubmitter preflight = new com.javaclaw.platform.execution.TaskSubmitter() {
                @Override public <T> com.javaclaw.platform.execution.TaskHandle<T> submit(
                        com.javaclaw.platform.execution.TaskSpec spec, com.javaclaw.platform.execution.ManagedTask<T> task) {
                    return tasks.submit(spec, context -> {
                        waiting.countDown(); new CountDownLatch(1).await(); return task.run(context);
                    });
                }
            };
            try (var manager = new GraphExecutionManager(registry, store, preflight)) {
                var run = manager.start(com.javaclaw.workflow.editor.WorkflowEditorModel.blank("queued"),
                        "coordinator", new GraphState(), GraphListener.NOOP, WorkflowExecutionServices.EMPTY);
                assertTrue(waiting.await(3, TimeUnit.SECONDS));
                manager.deleteThread("coordinator");
                assertFalse(manager.isActive(run.id()));
                assertNull(store.loadRun(run.id()));
            }
        }
    }
}
