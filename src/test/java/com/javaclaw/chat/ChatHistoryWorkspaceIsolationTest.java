package com.javaclaw.chat;

import com.javaclaw.application.chat.ChatHistoryApplicationService;
import com.javaclaw.application.chat.ChatHistoryApplicationService.DeliveryStatus;
import com.javaclaw.application.chat.ChatHistoryApplicationService.MessageRole;
import com.javaclaw.application.chat.ChatHistoryApplicationService.MessageSnapshot;
import com.javaclaw.application.chat.ChatHistoryApplicationService.SessionSnapshot;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.ThreadClient;
import com.javaclaw.framework.api.ThreadStartRequest;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHistoryWorkspaceIsolationTest {

    @TempDir
    Path tempDirectory;

    private AnnotationConfigApplicationContext context;

    @Test
    void untitledStateRoundTripsIndependentlyOfLocalizedTitle() {
        context = ApplicationContexts.createRoot(new DataRoot(tempDirectory.resolve("titles")));
        ChatHistoryApplicationService history = context.getBean(ChatHistoryApplicationService.class);
        String workspace = context.getBean(WorkspaceManager.class).getCurrentWorkspaceId();
        SessionSnapshot pending = new SessionSnapshot("pending", "新的对话", LocalDateTime.now(), true);
        SessionSnapshot explicit = new SessionSnapshot("explicit", "新的对话", LocalDateTime.now(), false);

        history.saveSessions(workspace, List.of(pending, explicit));

        var loaded = history.sessions(workspace);
        assertTrue(loaded.stream().anyMatch(value -> value.id().equals("pending")
                && value.autoTitlePending()));
        assertTrue(loaded.stream().anyMatch(value -> value.id().equals("explicit")
                && !value.autoTitlePending()));
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    @Test
    void delayedSourceSaveCannotOverwriteWorkspaceSelectedLater() throws Exception {
        context = ApplicationContexts.createRoot(
                new DataRoot(tempDirectory.resolve("data")));
        ChatHistoryApplicationService history =
                context.getBean(ChatHistoryApplicationService.class);
        WorkspaceManager workspaces = context.getBean(WorkspaceManager.class);
        String sourceWorkspace = workspaces.getCurrentWorkspaceId();
        String targetWorkspace = workspaces.createWorkspace("target").getId();

        SessionSnapshot sourceA = session("source-a");
        SessionSnapshot sourceB = session("source-b");
        SessionSnapshot target = session("target-a");
        history.saveMessages(sourceWorkspace, sourceA.id(), List.of(message("source-a")));
        history.saveMessages(sourceWorkspace, sourceB.id(), List.of(message("source-b")));
        history.saveSessions(sourceWorkspace, List.of(sourceA, sourceB));
        history.saveMessages(targetWorkspace, target.id(), List.of(message("target")));
        history.saveSessions(targetWorkspace, List.of(target));

        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> delayedSourceSave = CompletableFuture.runAsync(() -> {
            queued.countDown();
            await(release);
            history.saveSessions(sourceWorkspace, List.of(sourceA));
        });

        assertTrue(queued.await(5, TimeUnit.SECONDS));
        assertTrue(workspaces.switchWorkspace(targetWorkspace));
        release.countDown();
        delayedSourceSave.get(5, TimeUnit.SECONDS);

        assertEquals(Set.of(sourceA.id(), sourceB.id()), ids(history.sessions(sourceWorkspace)),
                "a delayed sidebar snapshot must not delete another durable conversation");
        assertEquals(Set.of(target.id()), ids(history.sessions(targetWorkspace)));
        assertEquals("source-a", history.messages(sourceWorkspace, sourceA.id())
                .getFirst().content());
        assertEquals("target", history.messages(targetWorkspace, target.id())
                .getFirst().content());
    }

    @Test
    void legacyChatRowsSurviveIdempotentThreadRegistration() {
        context = ApplicationContexts.createRoot(new DataRoot(tempDirectory.resolve("legacy")));
        ChatHistoryApplicationService history = context.getBean(ChatHistoryApplicationService.class);
        ThreadClient threads = context.getBean(ThreadClient.class);
        var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        String workspace = context.getBean(WorkspaceManager.class).getCurrentWorkspaceId();
        String legacyId = "legacy-session";
        String title = "旧会话标题";
        SessionSnapshot legacy = new SessionSnapshot(legacyId, title, LocalDateTime.now());
        history.saveMessages(workspace, legacyId, List.of(message("旧消息")));
        history.saveSessions(workspace, List.of(legacy));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_threads WHERE workspace_id=? AND thread_id=?",
                Integer.class, workspace, legacyId));

        RunScope scope = new RunScope(workspace, "local-user", legacyId);
        threads.start(ThreadStartRequest.root(scope, title));
        threads.start(ThreadStartRequest.root(scope, title));

        assertEquals(title, threads.get(scope).title());
        assertEquals(List.of("旧消息"), history.messages(workspace, legacyId).stream()
                .map(MessageSnapshot::content).toList());
        assertEquals(List.of(legacyId), history.sessions(workspace).stream()
                .map(SessionSnapshot::id).toList());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_threads WHERE workspace_id=? AND thread_id=?",
                Integer.class, workspace, legacyId));
        assertEquals(1, threads.events(scope, 0).stream()
                .filter(event -> event.type().equals("thread/started")).count());
    }

    @Test
    void deletedThreadRejectsLateMessageAndIndexWritesWhileArchiveRemainsRecoverable() {
        context = ApplicationContexts.createRoot(new DataRoot(tempDirectory.resolve("lifecycle")));
        var history = context.getBean(ChatHistoryApplicationService.class);
        var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        String workspace = context.getBean(WorkspaceManager.class).getCurrentWorkspaceId();
        SessionSnapshot source = session("protected-thread");
        jdbc.update("""
                INSERT INTO agent_threads(workspace_id,user_id,thread_id,title,status,
                    configuration_json,created_at,updated_at) VALUES (?,'local-user',?,?,'ACTIVE','{}',0,0)
                """, workspace, source.id(), source.title());
        history.saveMessages(workspace, source.id(), List.of(message("durable")));
        history.saveSessions(workspace, List.of(source));
        jdbc.update("UPDATE agent_threads SET status='ARCHIVED' WHERE thread_id=?", source.id());
        assertTrue(history.sessions(workspace).isEmpty());
        assertEquals("durable", history.messages(workspace, source.id()).getFirst().content());
        history.saveSessions(workspace, List.of());
        assertEquals("durable", history.messages(workspace, source.id()).getFirst().content());
        jdbc.update("UPDATE agent_threads SET status='DELETED' WHERE thread_id=?", source.id());
        history.delete(workspace, source.id());
        history.saveMessages(workspace, source.id(), List.of(message("late callback")));
        history.saveSessions(workspace, List.of(source));
        assertTrue(history.messages(workspace, source.id()).isEmpty());
        assertTrue(history.sessions(workspace).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE session_id=?",
                Integer.class, source.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE id=?",
                Integer.class, source.id()));
    }

    private static SessionSnapshot session(String id) {
        return new SessionSnapshot(id, id, LocalDateTime.now());
    }

    private static MessageSnapshot message(String content) {
        return new MessageSnapshot(
                MessageRole.USER,
                content,
                LocalDateTime.now(),
                List.of(),
                false,
                DeliveryStatus.COMPLETE,
                null);
    }

    private static Set<String> ids(List<SessionSnapshot> sessions) {
        return sessions.stream().map(SessionSnapshot::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待测试闩锁超时");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待测试闩锁被中断", interrupted);
        }
    }
}
