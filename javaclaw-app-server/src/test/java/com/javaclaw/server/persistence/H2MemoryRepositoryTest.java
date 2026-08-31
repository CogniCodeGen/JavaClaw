package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.knowledge.MemoryRepository;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2MemoryRepositoryTest {
    @TempDir
    Path temporary;

    @Test
    void preservesVersionsAndPinnedContentUntilExplicitRevisionCheckedConfirmation() {
        try (var store = new H2Persistence(temporary.resolve("memory-data"))) {
            var workspace = store.workspaces().create("memory", temporary.resolve("workspace"), "wsp");
            var repository = new H2MemoryRepository(store.database());
            String evidence = evidence(store, workspace, "请使用简体中文", false);
            var saved = repository.saveMemory(
                    draft("mem_language", workspace.id().value(), "请使用简体中文", true, List.of(evidence)), 0, "save");
            var proposal = repository.proposeMemory(
                    draft("mem_language", workspace.id().value(), "使用英文", false, List.of(evidence)),
                    saved.revision(),
                    true,
                    "建议修改语言",
                    "proposal");
            assertEquals("PENDING", proposal.state());
            assertEquals(
                    "请使用简体中文", repository.readMemory("mem_language").draft().content());
            var accepted = repository.reviewMemoryProposal(proposal.id(), true, proposal.revision(), "accept");
            assertEquals("ACCEPTED", accepted.state());
            assertEquals(accepted, repository.reviewMemoryProposal(proposal.id(), true, proposal.revision(), "accept"));
            var restored = repository.restoreMemory("mem_language", 1, 2, "restore");
            assertEquals(3, restored.revision());
            assertTrue(restored.draft().pinned());
            assertEquals(
                    List.of("请使用简体中文", "使用英文", "请使用简体中文"),
                    repository.memoryHistory("mem_language").stream()
                            .map(value -> value.draft().content())
                            .toList());
        }
    }

    @Test
    void staleProposalsCannotOverwriteNewUserContent() {
        try (var store = new H2Persistence(temporary.resolve("conflict-data"))) {
            var workspace = store.workspaces().create("memory", temporary.resolve("conflict-workspace"), "wsp");
            var repository = new H2MemoryRepository(store.database());
            String evidence = evidence(store, workspace, "简体中文", false);
            var saved = repository.saveMemory(
                    draft("memory", workspace.id().value(), "简体中文", true, List.of(evidence)), 0, "save");
            var proposal = repository.proposeMemory(
                    draft("memory", workspace.id().value(), "English", false, List.of(evidence)),
                    saved.revision(),
                    false,
                    "候选",
                    "propose");
            repository.saveMemory(
                    draft("memory", workspace.id().value(), "保留用户最新修改", true, List.of(evidence)), 1, "edit");
            assertThrows(
                    IllegalStateException.class,
                    () -> repository.reviewMemoryProposal(proposal.id(), true, 1, "accept"));
            assertEquals("保留用户最新修改", repository.readMemory("memory").draft().content());
            assertEquals(
                    "PENDING",
                    repository
                            .memoryProposals(workspace.id().value())
                            .getFirst()
                            .state());
        }
    }

    @Test
    void onlyLiteralLowRiskEvidenceMayBeAutomaticallyAcceptedAndQuestionsRemainProposals() {
        try (var store = new H2Persistence(temporary.resolve("evidence-data"))) {
            var workspace = store.workspaces().create("memory", temporary.resolve("evidence-workspace"), "wsp");
            var repository = new H2MemoryRepository(store.database());
            String statement = evidence(store, workspace, "我的默认回复语言是简体中文", false);
            var accepted = repository.proposeMemory(
                    draft(null, workspace.id().value(), "简体中文", false, List.of(statement)),
                    0,
                    true,
                    "用户明确陈述",
                    "literal");
            assertEquals("ACCEPTED", accepted.state());
            String question = evidence(store, workspace, "English 怎么学习？", false);
            var pending = repository.proposeMemory(
                    new MemoryRepository.MemoryDraft(
                            null,
                            workspace.id().value(),
                            "FACT",
                            "另一对象",
                            "language",
                            "English",
                            false,
                            List.of(question)),
                    0,
                    true,
                    "单次提问",
                    "question");
            assertEquals("PENDING", pending.state());
            String assistant = evidence(store, workspace, "我建议用户学习日语", true);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.proposeMemory(
                            draft(null, workspace.id().value(), "日语", false, List.of(assistant)),
                            0,
                            true,
                            "助手建议不是真实偏好",
                            "assistant"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.saveMemory(
                            draft(null, workspace.id().value(), "api_key=SECRET", false, List.of()), 0, "secret"));
        }
    }

    private static MemoryRepository.MemoryDraft draft(
            String id, String workspace, String content, boolean pinned, List<String> sources) {
        return new MemoryRepository.MemoryDraft(id, workspace, "FACT", "用户", "language", content, pinned, sources);
    }

    private static String evidence(
            H2Persistence store, com.javaclaw.core.api.Workspace workspace, String text, boolean assistant) {
        var thread =
                store.journal().createThread(workspace.id().value(), workspace.root(), "memory source", null, null);
        var config = new TurnConfig(
                "fake",
                "fake",
                "medium",
                workspace.root(),
                SandboxPolicy.readOnly(Set.of(workspace.root()), Set.of()),
                ApprovalPolicy.NEVER,
                Set.of(),
                java.util.Map.of());
        var turn = store.journal()
                .startTurn(new TurnStartCommand(thread.id(), List.of(new TurnInput.Text(text)), config, null));
        if (assistant) {
            return store.journal()
                    .appendItem(thread.id(), turn.id(), new ThreadItem.AgentMessage(text), ItemState.COMPLETED)
                    .id()
                    .value();
        }
        return store.journal().items(thread.id()).getFirst().id().value();
    }
}
