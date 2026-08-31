package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.conversation.ConversationWindow;
import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.agent.prompt.AgentsInstructionResolution;
import com.javaclaw.agent.prompt.PromptCatalog;
import com.javaclaw.agent.prompt.PromptCompiler;
import com.javaclaw.agent.prompt.PromptPurpose;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.sandbox.api.SandboxMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2PromptPersistenceTest {
    @TempDir
    Path temporary;

    @Test
    void migrationRemovesTheLegacyRuleTablesAndCreatesConversationWindows() {
        try (H2Persistence persistence = new H2Persistence(temporary.resolve("schema"))) {
            Set<String> tables = persistence.database().query(connection -> {
                java.util.HashSet<String> result = new java.util.HashSet<>();
                try (var query = connection.prepareStatement(
                                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'");
                        var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(rows.getString(1).toLowerCase(java.util.Locale.ROOT));
                    }
                }
                return Set.copyOf(result);
            });
            assertTrue(tables.contains("conversation_windows"));
            assertFalse(tables.contains("project_instructions"));
            assertFalse(tables.contains("project_instruction_revisions"));
        }
    }

    @Test
    void recordsActualPromptHashesWithoutPrivateBodiesOrChangingEventSequence() {
        try (H2Persistence persistence = new H2Persistence(temporary.resolve("archive"))) {
            var workspace = persistence.workspaces().create("workspace", temporary, "workspace");
            var profiles = new ProfileService(new H2ProfileRepository(persistence.database()), Set.of());
            var profile = profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "profile",
                            "Agent",
                            ProfileKind.CHAT,
                            "openai",
                            "gpt-5",
                            "PRIVATE_PERSONA",
                            Set.of(),
                            SandboxMode.READ_ONLY,
                            8,
                            8,
                            Map.of()),
                    0,
                    "profile");
            var config = profiles.resolve(profile.id(), workspace, null, null).turnConfig();
            var thread = persistence.journal().createThread(workspace.id().value(), temporary, "prompt", null, null);
            var turn = persistence
                    .journal()
                    .startTurn(new TurnStartCommand(
                            thread.id(), List.of(new TurnInput.Text("private user data")), config, "turn"));
            persistence.journal().transitionTurn(turn.id(), TurnStatus.QUEUED, TurnStatus.IN_PROGRESS, null);
            var compiled = new PromptCompiler(new PromptCatalog())
                    .compile(
                            PromptPurpose.CHAT,
                            config,
                            List.of(),
                            List.of(),
                            AgentsInstructionResolution.empty(temporary),
                            List.of(new ModelMessage(ModelMessage.Role.USER, "private user data", null)));
            long before =
                    persistence.journal().findThread(thread.id()).orElseThrow().lastSequence();
            persistence.journal().recordPromptSnapshot(thread.id(), turn.id(), compiled.snapshot(), 1);
            var archive = new H2PromptArchive(persistence.database());
            assertEquals(List.of(compiled.snapshot()), archive.list(turn.id()));
            assertFalse(archive.list(turn.id()).toString().contains("PRIVATE_PERSONA"));
            assertEquals(
                    before,
                    persistence.journal().findThread(thread.id()).orElseThrow().lastSequence());
            assertThrows(
                    RuntimeException.class,
                    () -> persistence.journal().recordPromptSnapshot(thread.id(), turn.id(), compiled.snapshot(), 1));
            persistence.journal().transitionTurn(turn.id(), TurnStatus.IN_PROGRESS, TurnStatus.COMPLETED, null);
            assertThrows(
                    IllegalStateException.class,
                    () -> persistence.journal().recordPromptSnapshot(thread.id(), turn.id(), compiled.snapshot(), 2));
        }
    }

    @Test
    void conversationWindowSurvivesRestartAndFailedCompactionDoesNotReplaceIt() throws Exception {
        Path root = temporary.resolve("window-data");
        Path workspaceRoot = java.nio.file.Files.createDirectories(temporary.resolve("window-workspace"));
        String threadId;
        String successfulItemId;
        try (H2Persistence persistence = new H2Persistence(root)) {
            var workspace = persistence.workspaces().create("window", workspaceRoot, "window");
            var profiles = new ProfileService(new H2ProfileRepository(persistence.database()), Set.of());
            var profile = profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "window-profile",
                            "Window",
                            ProfileKind.CHAT,
                            "anthropic",
                            "fake",
                            "",
                            Set.of(),
                            SandboxMode.READ_ONLY,
                            8,
                            8,
                            Map.of()),
                    0,
                    "window-profile");
            var config = profiles.resolve(profile.id(), workspace, null, null).turnConfig();
            var thread =
                    persistence.journal().createThread(workspace.id().value(), workspaceRoot, "window", null, null);
            threadId = thread.id().value();
            var turn = persistence
                    .journal()
                    .startTurn(new TurnStartCommand(
                            thread.id(), List.of(new TurnInput.Text("保留原始消息")), config, "window-turn"));
            persistence.journal().transitionTurn(turn.id(), TurnStatus.QUEUED, TurnStatus.IN_PROGRESS, null);
            persistence
                    .journal()
                    .appendItem(thread.id(), turn.id(), new ThreadItem.UserMessage("保留原始消息"), ItemState.COMPLETED);
            persistence
                    .journal()
                    .saveProviderConversationState(
                            thread.id(),
                            "fake",
                            1,
                            new ProviderConversationState("anthropic", 1, "[{\"type\":\"message\"}]", 3, false),
                            new ModelUsage(4, 2, 0));
            var successful = persistence.journal().startItem(thread.id(), turn.id(), "contextCompaction");
            successfulItemId = successful.id().value();
            persistence
                    .journal()
                    .completeCompaction(
                            successful.id(),
                            new ThreadItem.ContextCompaction(),
                            new ConversationWindow.Replacement(
                                    ConversationWindow.Strategy.SUMMARY,
                                    "anthropic",
                                    "fake",
                                    persistence
                                            .journal()
                                            .findThread(thread.id())
                                            .orElseThrow()
                                            .lastSequence(),
                                    1,
                                    "已完成：窗口持久化。待办：重启核验。",
                                    List.of("最近用户消息"),
                                    new ModelUsage(8, 3, 0)));
            var failed = persistence.journal().startItem(thread.id(), turn.id(), "contextCompaction");
            persistence.journal().failItem(failed.id(), "compaction_failed", "空摘要未替换活动窗口。", false);
            assertEquals(
                    successfulItemId,
                    persistence
                            .journal()
                            .activeConversationWindow(thread.id())
                            .orElseThrow()
                            .compactionItemId()
                            .value());
            persistence.journal().transitionTurn(turn.id(), TurnStatus.IN_PROGRESS, TurnStatus.COMPLETED, null);
        }

        try (H2Persistence reopened = new H2Persistence(root)) {
            var id = new com.javaclaw.core.api.ThreadId(threadId);
            ConversationWindow active =
                    reopened.journal().activeConversationWindow(id).orElseThrow();
            assertEquals(1, active.number());
            assertEquals(ConversationWindow.Strategy.SUMMARY, active.strategy());
            assertEquals("已完成：窗口持久化。待办：重启核验。", active.payload());
            assertEquals(List.of("最近用户消息"), active.retainedUserMessages());
            assertEquals(successfulItemId, active.compactionItemId().value());
            var items = reopened.journal().items(id);
            assertTrue(items.stream().anyMatch(item -> item.item() instanceof ThreadItem.UserMessage));
            assertTrue(items.stream()
                    .anyMatch(item -> item.id().value().equals(successfulItemId)
                            && item.item() instanceof ThreadItem.ContextCompaction
                            && item.state() == ItemState.COMPLETED));
            assertTrue(items.stream().anyMatch(item -> item.state() == ItemState.FAILED));
        }
    }

    @Test
    void backfillsLegacySummaryIntoWindowAndRewritesItemToIdOnly() throws Exception {
        Path root = temporary.resolve("legacy-window-data");
        Path workspaceRoot = java.nio.file.Files.createDirectories(temporary.resolve("legacy-window-workspace"));
        String threadId;
        try (H2Persistence persistence = new H2Persistence(root)) {
            var workspace = persistence.workspaces().create("legacy", workspaceRoot, "legacy");
            var profiles = new ProfileService(new H2ProfileRepository(persistence.database()), Set.of());
            var profile = profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "legacy-profile",
                            "Legacy",
                            ProfileKind.CHAT,
                            "openai",
                            "gpt-5",
                            "",
                            Set.of(),
                            SandboxMode.READ_ONLY,
                            8,
                            8,
                            Map.of()),
                    0,
                    "legacy-profile");
            var config = profiles.resolve(profile.id(), workspace, null, null).turnConfig();
            var thread =
                    persistence.journal().createThread(workspace.id().value(), workspaceRoot, "legacy", null, null);
            threadId = thread.id().value();
            var turn = persistence
                    .journal()
                    .startTurn(new TurnStartCommand(
                            thread.id(), List.of(new TurnInput.Text("旧对话")), config, "legacy-turn"));
            persistence.journal().transitionTurn(turn.id(), TurnStatus.QUEUED, TurnStatus.IN_PROGRESS, null);
            var item = persistence
                    .journal()
                    .appendItem(thread.id(), turn.id(), new ThreadItem.ContextCompaction(), ItemState.COMPLETED);
            persistence.database().transaction(connection -> {
                try (var update = connection.prepareStatement(
                        "UPDATE items SET kind='compaction', payload_json=? WHERE item_id=?")) {
                    update.setString(
                            1, "{\"kind\":\"compaction\",\"summary\":\"旧版摘要正文\"," + "\"compactedThroughSequence\":42}");
                    update.setString(2, item.id().value());
                    assertEquals(1, update.executeUpdate());
                }
                return null;
            });
            persistence.journal().transitionTurn(turn.id(), TurnStatus.IN_PROGRESS, TurnStatus.COMPLETED, null);
        }

        try (H2Persistence reopened = new H2Persistence(root)) {
            var id = new com.javaclaw.core.api.ThreadId(threadId);
            ConversationWindow active =
                    reopened.journal().activeConversationWindow(id).orElseThrow();
            assertEquals(ConversationWindow.Strategy.SUMMARY, active.strategy());
            assertEquals("旧版摘要正文", active.payload());
            assertEquals(42, active.coveredSequence());
            var item = reopened.journal().items(id).stream()
                    .filter(value -> "contextCompaction".equals(value.kind()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("contextCompaction", item.kind());
            assertTrue(item.item() instanceof ThreadItem.ContextCompaction);
            String payload = reopened.database().query(connection -> {
                try (var query = connection.prepareStatement("SELECT payload_json FROM items WHERE item_id=?")) {
                    query.setString(1, item.id().value());
                    try (var row = query.executeQuery()) {
                        assertTrue(row.next());
                        return row.getString(1);
                    }
                }
            });
            assertFalse(payload.contains("summary"));
        }
    }
}
