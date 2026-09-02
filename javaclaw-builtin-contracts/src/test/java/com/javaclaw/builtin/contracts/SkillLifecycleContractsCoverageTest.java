package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.TurnId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLifecycleContractsCoverageTest {
    private static final Instant NOW = BuiltinContractsFixtures.NOW;
    private static final String DIGEST = "a".repeat(64);

    @Test
    void draftPublishCatalogAndHistoryPreserveExactRevisionsAndDigests() {
        SkillContracts.Resource markdown = new SkillContracts.Resource("guide", "text/markdown", DIGEST, false);
        SkillContracts.Resource java =
                new SkillContracts.Resource("runner", SkillContracts.JAVA_SOURCE_MEDIA_TYPE, "b".repeat(64), true);
        SkillContracts.Draft draft = new SkillContracts.Draft(
                "skill", 2, "Review", "Review code", "Read carefully", List.of(markdown, java), NOW, NOW);
        SkillContracts.PublishedSkill published = new SkillContracts.PublishedSkill(
                "skill",
                3,
                draft.revision(),
                "c".repeat(64),
                draft.name(),
                draft.description(),
                draft.instructions(),
                draft.resources(),
                true,
                NOW,
                NOW);
        SkillContracts.Summary summary =
                new SkillContracts.Summary("skill", 3, published.digest(), "Review", "Review code");
        SkillContracts.DraftHistoryEntry entry = new SkillContracts.DraftHistoryEntry(2, draft, false, NOW);
        ArrayList<SkillContracts.DraftHistoryEntry> entries = new ArrayList<>(List.of(entry));
        SkillContracts.DraftHistoryPage page = new SkillContracts.DraftHistoryPage(entries, true);
        entries.clear();

        assertEquals(2, new SkillContracts.PublishRequest("skill", 2).draftRevision());
        assertFalse(new SkillContracts.EnableRequest("skill", false).enabled());
        assertEquals("skill", new SkillContracts.Key("skill").id());
        assertEquals(
                "Read carefully",
                new SkillContracts.SaveContentRequest("skill", "Review", "Review code", "Read carefully")
                        .instructions());
        assertEquals("guide", new SkillContracts.RemoveResourceRequest("skill", "guide").resourceId());
        assertEquals(
                summary,
                new SkillContracts.SearchResult(List.of(summary), published.digest())
                        .matches()
                        .getFirst());
        assertEquals(3, new SkillContracts.PublishedReadRequest("skill", 3, published.digest()).revision());
        assertEquals(50, new SkillContracts.HistoryRequest("skill", 0, 50).limit());
        assertEquals(2, new SkillContracts.DraftRestoreRequest("skill", 2).sourceRevision());
        assertEquals(1, page.entries().size());
        assertTrue(page.hasMore());
        SkillContracts.CatalogSnapshot snapshot = new SkillContracts.CatalogSnapshot(
                TurnId.parse(UUID.randomUUID().toString()), List.of(summary), published.digest(), NOW);
        assertEquals(published.digest(), snapshot.digest());
    }

    @Test
    void attachmentResourcesAllowOnlyBoundedJavaOrJshellExecution() {
        AttachmentRef javaAttachment =
                new AttachmentRef(DIGEST, SkillContracts.JAVA_SOURCE_MEDIA_TYPE, "Runner.java", 128);
        AttachmentRef jshellAttachment =
                new AttachmentRef("b".repeat(64), SkillContracts.JSHELL_MEDIA_TYPE, "script.jsh", 64);
        SkillContracts.AddResourceRequest java =
                new SkillContracts.AddResourceRequest("skill", "runner", javaAttachment, true);
        SkillContracts.AddResourceRequest jshell =
                new SkillContracts.AddResourceRequest("skill", "script", jshellAttachment, true);

        assertTrue(java.executable());
        assertEquals(SkillContracts.JSHELL_MEDIA_TYPE, jshell.attachment().mediaType());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.AddResourceRequest(
                        "skill", "empty", new AttachmentRef(DIGEST, "text/plain", "empty.txt", 0), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.AddResourceRequest(
                        "skill", "unsafe", new AttachmentRef(DIGEST, "text/x-python", "script.py", 1), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.PublishedSkill(
                        "skill",
                        1,
                        1,
                        DIGEST,
                        "name",
                        "description",
                        "instructions",
                        List.of(javaResource("same"), javaResource("same")),
                        true,
                        NOW,
                        NOW));
    }

    @Test
    void proposalAdoptionAlwaysProducesDraftAndOtherStatesNeverCarryDraftIdentity() {
        SkillContracts.ProposeRequest candidate =
                new SkillContracts.ProposeRequest("proposal", "draft", "Review", "Review code", "Read carefully");
        SkillContracts.Proposal pending = proposal(candidate, SkillContracts.ProposalState.PENDING, Optional.empty());
        SkillContracts.Proposal adopted =
                proposal(candidate, SkillContracts.ProposalState.ADOPTED_AS_DRAFT, Optional.of("draft"));
        SkillContracts.Proposal rejected = proposal(candidate, SkillContracts.ProposalState.REJECTED, Optional.empty());

        assertEquals("proposal", new SkillContracts.ProposalDecision("proposal").id());
        assertEquals(SkillContracts.ProposalState.PENDING, pending.state());
        assertEquals("draft", adopted.draftId().orElseThrow());
        assertEquals(SkillContracts.ProposalState.REJECTED, rejected.state());
        assertThrows(
                IllegalArgumentException.class,
                () -> proposal(candidate, SkillContracts.ProposalState.PENDING, Optional.of("draft")));
        assertThrows(
                IllegalArgumentException.class,
                () -> proposal(candidate, SkillContracts.ProposalState.ADOPTED_AS_DRAFT, Optional.empty()));
    }

    @Test
    void resourceExecutionRequiresPublishedExecutableResourceAndBoundedArguments() {
        SkillContracts.PublishedReadRequest skill = new SkillContracts.PublishedReadRequest("skill", 1, DIGEST);
        SkillContracts.Resource executable = javaResource("runner");
        SkillContracts.ResourceExecutionRequest request =
                new SkillContracts.ResourceExecutionRequest(skill, "runner", List.of("--check"));
        SkillContracts.ResourceExecutionInvocation status = new SkillContracts.ResourceExecutionInvocation(
                SkillContracts.ResourceExecutionOperation.STATUS, Optional.empty(), List.of());
        SkillContracts.ResourceExecutionInvocation execute = new SkillContracts.ResourceExecutionInvocation(
                SkillContracts.ResourceExecutionOperation.EXECUTE, Optional.of(executable), List.of("--check"));
        SkillContracts.ResourceExecutionResult result = new SkillContracts.ResourceExecutionResult(0, "ok", false, 10);

        assertEquals("runner", request.resourceId());
        assertTrue(status.resource().isEmpty());
        assertEquals(executable, execute.resource().orElseThrow());
        assertEquals("ok", result.output());
        assertTrue(new SkillContracts.ResourceExecutionAvailability(true, "").executable());
        assertEquals(
                "sandbox unavailable",
                new SkillContracts.ResourceExecutionAvailability(false, " sandbox unavailable ").reason());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.ResourceExecutionAvailability(true, "unexpected"));
        assertThrows(IllegalArgumentException.class, () -> new SkillContracts.ResourceExecutionAvailability(false, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.ResourceExecutionInvocation(
                        SkillContracts.ResourceExecutionOperation.STATUS, Optional.empty(), List.of("argument")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.ResourceExecutionInvocation(
                        SkillContracts.ResourceExecutionOperation.EXECUTE,
                        Optional.of(new SkillContracts.Resource("guide", "text/plain", DIGEST, false)),
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.ResourceExecutionRequest(
                        skill, "runner", java.util.Collections.nCopies(17, "argument")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.ResourceExecutionRequest(skill, "runner", List.of("bad\0argument")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.ResourceExecutionResult(-1, "failed", false, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.ResourceExecutionResult(0, "failed", false, -1));
    }

    @Test
    void skillHistorySearchAndTimestampsRejectStaleOrInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> new SkillContracts.HistoryRequest("skill", -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new SkillContracts.HistoryRequest("skill", 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.Draft(
                        "skill", 1, "name", "description", "instructions", List.of(), NOW, NOW.minusSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.PublishedSkill(
                        "skill",
                        1,
                        1,
                        DIGEST,
                        "name",
                        "description",
                        "instructions",
                        List.of(),
                        true,
                        NOW,
                        NOW.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new SkillContracts.SearchRequest("query", 0));
    }

    private static SkillContracts.Resource javaResource(String id) {
        return new SkillContracts.Resource(id, SkillContracts.JAVA_SOURCE_MEDIA_TYPE, DIGEST, true);
    }

    private static SkillContracts.Proposal proposal(
            SkillContracts.ProposeRequest candidate, SkillContracts.ProposalState state, Optional<String> draftId) {
        return new SkillContracts.Proposal("proposal", 1, candidate, state, draftId, NOW, NOW);
    }
}
