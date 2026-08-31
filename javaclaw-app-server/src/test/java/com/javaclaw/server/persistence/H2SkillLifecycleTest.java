package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.knowledge.LearningRepository;
import com.javaclaw.agent.tool.SkillManifests;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2SkillLifecycleTest {
    @TempDir
    Path temporary;

    @Test
    void rollbackCreatesNewRevisionAndNeverReenablesDisabledSkill() {
        try (var store = new H2Persistence(temporary.resolve("versions"))) {
            var repository = new H2KnowledgeRepository(store.database());
            repository.putSkill("skill", "review", "1", "{\"instructions\":\"first\"}", true, 0, "create");
            repository.putSkill("skill", "review", "2", "{\"instructions\":\"user change\"}", true, 1, "edit");
            repository.setSkillEnabled("skill", false, 2, "disable");
            var restored = repository.restoreSkill("skill", 1, 3, "restore");
            assertEquals(4, restored.revision());
            assertFalse(restored.enabled());
            assertEquals("first", SkillManifests.instructions(restored.manifest()));
            repository.setSkillEnabled("skill", true, 4, "enable");
            assertEquals(restored, repository.restoreSkill("skill", 1, 3, "restore"));
            assertEquals(5, repository.skillHistory("skill").size());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SkillManifests.validate(
                            "{\"instructions\":\"x\",\"resources\":[{\"path\":\"../escape.jsh\",\"content\":\"x\",\"executable\":true}]}"));
        }
    }

    @Test
    void learningDeduplicatesAndRejectsStaleApprovalWithoutOverwritingUserContent() {
        try (var store = new H2Persistence(temporary.resolve("proposals"))) {
            var workspace = store.workspaces().create("skills", temporary.resolve("workspace"), "workspace");
            var repository = new H2KnowledgeRepository(store.database());
            String evidence = evidence(store, workspace, 0);
            String manifest = "{\"instructions\":\"Run the verified tests and report results.\"}";
            var draft = new LearningRepository.SkillDraft(
                    workspace.id().value(), null, "Test", "1", manifest, List.of(evidence), 0);
            var proposal = repository.proposeSkill(draft, true, "已通过测试", "propose");
            assertEquals("PENDING", proposal.state());
            assertEquals(
                    proposal.id(),
                    repository.proposeSkill(draft, true, "同一候选", "duplicate").id());
            var accepted = repository.reviewSkillProposal(proposal.id(), true, 1, "accept");
            assertEquals("ACCEPTED", accepted.state());
            var update = repository.proposeSkill(
                    new LearningRepository.SkillDraft(
                            workspace.id().value(),
                            null,
                            "Test",
                            "2",
                            "{\"instructions\":\"Next validated version.\"}",
                            List.of(evidence),
                            0),
                    true,
                    "改进",
                    "update");
            assertEquals(proposal.draft().targetId(), update.draft().targetId());
            repository.putSkill(
                    update.draft().targetId(), "Test", "user", "{\"instructions\":\"用户改写\"}", true, 1, "user-edit");
            assertThrows(
                    IllegalStateException.class, () -> repository.reviewSkillProposal(update.id(), true, 1, "stale"));
            assertEquals(
                    "用户改写",
                    SkillManifests.instructions(repository
                            .findSkill(update.draft().targetId())
                            .orElseThrow()
                            .manifest()));
            assertEquals(1, repository.listSkills().size());
            assertEquals(2, repository.skillProposals(workspace.id().value()).size());
        }
    }

    @Test
    void autoModeOnlyAcceptsNewNonExecutableContentAndRequiresRealSuccessEvidence() {
        try (var store = new H2Persistence(temporary.resolve("automatic"))) {
            var workspace = store.workspaces().create("skills", temporary.resolve("automatic-workspace"), "wsp");
            var repository = new H2KnowledgeRepository(store.database());
            repository.saveLearningSettings(workspace.id().value(), "AUTO", true, 0, "configure");
            String success = evidence(store, workspace, 0);
            var accepted = repository.proposeSkill(
                    new LearningRepository.SkillDraft(
                            workspace.id().value(),
                            null,
                            "Simple",
                            "1",
                            "{\"instructions\":\"Check the exit code.\"}",
                            List.of(success),
                            0),
                    true,
                    "有限说明",
                    "simple");
            assertEquals("ACCEPTED", accepted.state());
            String script =
                    "{\"instructions\":\"Use the script.\",\"resources\":[{\"path\":\"test.jsh\",\"content\":\"1+1\",\"executable\":true}]}";
            var pending = repository.proposeSkill(
                    new LearningRepository.SkillDraft(
                            workspace.id().value(), null, "Script", "1", script, List.of(success), 0),
                    true,
                    "含脚本必须确认",
                    "script");
            assertEquals("PENDING", pending.state());
            String failed = evidence(store, workspace, 1);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.proposeSkill(
                            new LearningRepository.SkillDraft(
                                    workspace.id().value(),
                                    null,
                                    "Unverified",
                                    "1",
                                    "{\"instructions\":\"not verified\"}",
                                    List.of(failed),
                                    0),
                            true,
                            "失败不能当经验",
                            "failed"));
            assertTrue(repository.findSkill(accepted.draft().targetId()).isPresent());
        }
    }

    private static String evidence(H2Persistence store, com.javaclaw.core.api.Workspace workspace, int exitCode) {
        var thread = store.journal().createThread(workspace.id().value(), workspace.root(), "skill source", null, null);
        var config = new TurnConfig(
                "fake",
                "fake",
                "medium",
                workspace.root(),
                SandboxPolicy.readOnly(Set.of(workspace.root()), Set.of()),
                ApprovalPolicy.NEVER,
                Set.of(),
                Map.of());
        var turn = store.journal()
                .startTurn(new TurnStartCommand(thread.id(), List.of(new TurnInput.Text("运行测试")), config, null));
        return store.journal()
                .appendItem(
                        thread.id(),
                        turn.id(),
                        new ThreadItem.CommandExecution(List.of("test"), exitCode, "verified", "", false, false),
                        ItemState.COMPLETED)
                .id()
                .value();
    }
}
