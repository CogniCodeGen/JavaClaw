package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySkillLifecycleTest {
    @Test
    void memoryKeepsImmutableHistoryAcrossTombstoneAndRestore() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        MemoryContracts.Memory created = support.decode(
                started.command(
                        support.request("create", createMemory("memory", "最初内容"), Optional.of("memory-create"), 0)),
                MemoryContracts.Memory.class);
        assertEquals(1, created.revision());

        MemoryContracts.Memory updated = support.decode(
                started.command(
                        support.request("update", updateMemory("memory", "纠正内容"), Optional.of("memory-update"), 1)),
                MemoryContracts.Memory.class);
        assertEquals(2, updated.revision());
        started.command(
                support.request("tombstone", new MemoryContracts.Key("memory"), Optional.of("memory-delete"), 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.query(support.request("read", new MemoryContracts.Key("memory"), Optional.empty(), 0)));

        MemoryContracts.HistoryPage history = memoryHistory(support, started);
        assertEquals(
                List.of(1L, 2L, 3L),
                history.entries().stream()
                        .map(MemoryContracts.HistoryEntry::revision)
                        .toList());
        assertTrue(history.entries().getLast().tombstone());
        MemoryContracts.Memory restored = support.decode(
                started.command(support.request(
                        "restore", new MemoryContracts.RestoreRequest("memory", 1), Optional.of("memory-restore"), 3)),
                MemoryContracts.Memory.class);
        assertEquals(4, restored.revision());
        assertEquals("最初内容", restored.content());
        assertEquals(
                List.of(1L, 2L, 3L, 4L),
                memoryHistory(support, started).entries().stream()
                        .map(MemoryContracts.HistoryEntry::revision)
                        .toList());
    }

    @Test
    void memoryAutoLearningAcceptsOnlyVerifiableLowRiskFact() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        setLearningPolicy(support, started, MemoryContracts.LearningPolicy.AUTO_LOW_RISK, 0, "auto-policy");
        MemoryContracts.LearningResult accepted = submitLearning(
                support,
                started,
                proposal(support, "safe", MemoryContracts.MemoryKind.FACT, "JavaClaw 使用 v5"),
                "safe-proposal");
        assertEquals(MemoryContracts.LearningAction.AUTO_ACCEPTED, accepted.action());
        assertTrue(accepted.memory().isPresent());

        support.evidenceAvailable = false;
        support.uncertainEvidence = true;
        MemoryContracts.LearningResult risky = submitLearning(
                support,
                started,
                proposal(support, "risky", MemoryContracts.MemoryKind.PERSONA, "可能保存 password，且工具结果未知"),
                "risky-proposal");
        assertEquals(MemoryContracts.LearningAction.PROPOSED, risky.action());
        Set<MemoryContracts.ProposalConcern> concerns =
                risky.proposal().orElseThrow().concerns();
        assertTrue(concerns.containsAll(Set.of(
                MemoryContracts.ProposalConcern.PERSONA,
                MemoryContracts.ProposalConcern.SENSITIVE,
                MemoryContracts.ProposalConcern.SPECULATIVE,
                MemoryContracts.ProposalConcern.UNCERTAIN_EVIDENCE,
                MemoryContracts.ProposalConcern.UNKNOWN_OUTCOME,
                MemoryContracts.ProposalConcern.CONFLICT)));
        assertTrue(risky.memory().isEmpty());

        setLearningPolicy(support, started, MemoryContracts.LearningPolicy.OFF, 1, "off-policy");
        MemoryContracts.LearningResult ignored = submitLearning(
                support, started, proposal(support, "off", MemoryContracts.MemoryKind.FACT, "不会保存"), "off-proposal");
        assertEquals(MemoryContracts.LearningAction.IGNORED, ignored.action());
        assertTrue(ignored.proposal().isEmpty());
    }

    @Test
    void memoryAutoLearningRejectsEvidenceFromAnotherWorkspace() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        setLearningPolicy(support, started, MemoryContracts.LearningPolicy.AUTO_LOW_RISK, 0, "auto-policy");
        String content = "只能写入当前 Workspace";
        MemoryContracts.Source foreign =
                new MemoryContracts.Source(WorkspaceId.random(), ThreadId.random(), ItemId.random(), content);
        MemoryContracts.LearningProposalRequest candidate = new MemoryContracts.LearningProposalRequest(
                "foreign", MemoryContracts.MemoryKind.FACT, "workspace", content, Set.of("v5"), foreign);

        MemoryContracts.LearningResult result = submitLearning(support, started, candidate, "foreign-proposal");

        assertEquals(MemoryContracts.LearningAction.PROPOSED, result.action());
        assertTrue(result.proposal()
                .orElseThrow()
                .concerns()
                .contains(MemoryContracts.ProposalConcern.UNCERTAIN_EVIDENCE));
        assertTrue(result.memory().isEmpty());
    }

    @Test
    void memoryViewBindsLearningSettingsToAuthoritativeRevision() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        ViewQueryRequest query = new ViewQueryRequest("settings", Map.of(), "", 1, Optional.empty());
        ViewQueryResult initial = support.decode(
                started.query(support.request("view.settings", query, Optional.empty(), 0)), ViewQueryResult.class);
        MemoryContracts.LearningSettings initialSettings =
                support.payloads.decode(initial.values(), MemoryContracts.LearningSettings.class);
        ViewSchema.Form form = (ViewSchema.Form)
                MemoryExtensionPresentation.learningView().nodes().getFirst();

        assertEquals(MemoryContracts.LearningPolicy.SUGGEST, initialSettings.policy());
        assertEquals(0, initial.revision());
        assertEquals(
                new com.javaclaw.extension.spi.ExpectedRevisionBinding.SourceRevision("settings"),
                form.submit().expectedRevision());
        setLearningPolicy(support, started, MemoryContracts.LearningPolicy.AUTO_LOW_RISK, 0, "view-policy");
        ViewQueryResult updated = support.decode(
                started.query(support.request("view.settings", query, Optional.empty(), 0)), ViewQueryResult.class);
        assertEquals(1, updated.revision());
    }

    @Test
    void memoryProposalRequiresOneExplicitDecisionAndAcceptanceCreatesMemory() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        MemoryContracts.LearningResult submitted = submitLearning(
                support,
                started,
                proposal(support, "manual", MemoryContracts.MemoryKind.FACT, "需要人工采纳"),
                "manual-proposal");
        MemoryContracts.Proposal accepted = support.decode(
                started.command(support.request(
                        "proposal/accept",
                        new MemoryContracts.ProposalDecision("manual"),
                        Optional.of("manual-accept"),
                        1)),
                MemoryContracts.Proposal.class);

        assertEquals(
                MemoryContracts.ProposalState.PENDING,
                submitted.proposal().orElseThrow().state());
        assertEquals(MemoryContracts.ProposalState.ACCEPTED, accepted.state());
        assertEquals(
                "需要人工采纳",
                support.decode(
                                started.query(support.request(
                                        "read",
                                        new MemoryContracts.Key(
                                                accepted.memoryId().orElseThrow()),
                                        Optional.empty(),
                                        0)),
                                MemoryContracts.Memory.class)
                        .content());
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "proposal/reject",
                        new MemoryContracts.ProposalDecision("manual"),
                        Optional.of("manual-reject"),
                        2)));
    }

    @Test
    void skillRequiresDraftPublishEnableAndInvalidatesFrozenReadOnDisable() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        SkillContracts.Proposal proposal = submitAndAdoptSkill(support, started);
        assertEquals(SkillContracts.ProposalState.ADOPTED_AS_DRAFT, proposal.state());
        assertThrows(
                IllegalArgumentException.class,
                () -> started.query(
                        support.request("published/read", new SkillContracts.Key("review"), Optional.empty(), 0)));

        SkillContracts.PublishedSkill published = publish(support, started, 0, 1, "publish-review");
        assertFalse(published.enabled());
        SkillContracts.SearchResult hidden = searchSkill(support, started, Optional.empty());
        assertTrue(hidden.matches().isEmpty());
        SkillContracts.PublishedSkill enabled = enable(support, started, 1, true, "enable-review");
        TurnId turnId = TurnId.random();
        SkillContracts.SearchResult frozen = searchSkill(support, started, Optional.of(turnId));
        assertEquals(
                List.of("review"),
                frozen.matches().stream().map(SkillContracts.Summary::id).toList());
        SkillContracts.Summary summary = frozen.matches().getFirst();
        SkillContracts.PublishedSkill exact = readFrozen(support, started, turnId, summary);
        assertEquals(enabled, exact);

        enable(support, started, enabled.revision(), false, "disable-review");
        assertThrows(IllegalArgumentException.class, () -> readFrozen(support, started, turnId, summary));
        SkillContracts.ResourceExecutionAvailability availability = support.decode(
                started.query(support.request(
                        "resource/execution/availability", new SkillContracts.Key("review"), Optional.empty(), 0)),
                SkillContracts.ResourceExecutionAvailability.class);
        assertFalse(availability.executable());
    }

    @Test
    void skillDraftHistorySurvivesTombstoneAndProposalNeverPublishesDirectly() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        submitAndAdoptSkill(support, started);
        SkillContracts.Draft updated = support.decode(
                started.command(support.request("draft/save-content", draft("更新后的指令"), Optional.of("draft-update"), 1)),
                SkillContracts.Draft.class);
        assertEquals(2, updated.revision());
        started.command(
                support.request("draft/tombstone", new SkillContracts.Key("review"), Optional.of("draft-delete"), 2));
        SkillContracts.DraftHistoryPage history = skillHistory(support, started);
        assertEquals(
                List.of(1L, 2L, 3L),
                history.entries().stream()
                        .map(SkillContracts.DraftHistoryEntry::revision)
                        .toList());
        assertTrue(history.entries().getLast().tombstone());

        SkillContracts.Draft restored = support.decode(
                started.command(support.request(
                        "draft/restore",
                        new SkillContracts.DraftRestoreRequest("review", 1),
                        Optional.of("draft-restore"),
                        3)),
                SkillContracts.Draft.class);
        assertEquals(4, restored.revision());
        assertEquals("执行代码审查", restored.instructions());
        assertThrows(
                IllegalArgumentException.class,
                () -> started.query(
                        support.request("published/read", new SkillContracts.Key("review"), Optional.empty(), 0)));
    }

    @Test
    void skillPublishedRevisionChangeInvalidatesExistingTurnSnapshot() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        submitAndAdoptSkill(support, started);
        publish(support, started, 0, 1, "publish-review");
        enable(support, started, 1, true, "enable-review");
        TurnId turnId = TurnId.random();
        SkillContracts.Summary frozen =
                searchSkill(support, started, Optional.of(turnId)).matches().getFirst();
        support.decode(
                started.command(
                        support.request("draft/save-content", draft("新的已审阅指令"), Optional.of("draft-update"), 1)),
                SkillContracts.Draft.class);

        publish(support, started, 2, 2, "publish-updated");

        assertThrows(IllegalArgumentException.class, () -> readFrozen(support, started, turnId, frozen));
    }

    @Test
    void skillExecutesOnlyExactFrozenPublishedJavaResourceThroughIsolatedService() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SkillContracts.Resource resource =
                new SkillContracts.Resource("Main.java", SkillContracts.JAVA_SOURCE_MEDIA_TYPE, "a".repeat(64), true);
        support.service = (caller, serviceId, payload) -> {
            assertEquals(SkillContracts.RESOURCE_EXECUTION_SERVICE, serviceId);
            SkillContracts.ResourceExecutionInvocation invocation =
                    support.payloads.decode(payload, SkillContracts.ResourceExecutionInvocation.class);
            if (invocation.operation() == SkillContracts.ResourceExecutionOperation.STATUS) {
                return support.payloads.encode(new SkillContracts.ResourceExecutionAvailability(true, ""));
            }
            assertEquals(resource, invocation.resource().orElseThrow());
            assertEquals(List.of("one"), invocation.arguments());
            return support.payloads.encode(new SkillContracts.ResourceExecutionResult(0, "ok", false, 12));
        };
        var started = support.start(new SkillExtension());
        SkillContracts.PublishedSkill enabled = publishExecutableSkill(support, started, resource);
        TurnId turnId = TurnId.random();
        SkillContracts.ResourceExecutionRequest execute = freezeResourceExecution(support, started, resource, turnId);
        SkillContracts.ResourceExecutionResult result = support.decode(
                started.tool(
                        "skill.resource.execute.tool",
                        support.request("execute", execute, Optional.empty(), 0, Optional.of(turnId))),
                SkillContracts.ResourceExecutionResult.class);

        assertEquals("ok", result.output());
        assertEquals(
                ToolRisk.PROCESS,
                tool(started, "skill.resource.execute.tool").descriptor().risk());
        SkillContracts.ResourceExecutionAvailability availability = support.decode(
                started.query(support.request("resource/execution/availability", Map.of(), Optional.empty(), 0)),
                SkillContracts.ResourceExecutionAvailability.class);
        assertTrue(availability.executable());

        started.command(support.request(
                "enable",
                new SkillContracts.EnableRequest("runner", false),
                Optional.of("runner-disable"),
                enabled.revision()));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.tool(
                        "skill.resource.execute.tool",
                        support.request("execute", execute, Optional.empty(), 0, Optional.of(turnId))));
    }

    private SkillContracts.PublishedSkill publishExecutableSkill(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            SkillContracts.Resource resource)
            throws Exception {
        SkillContracts.SaveContentRequest draft =
                new SkillContracts.SaveContentRequest("runner", "Java 运行", "执行已审阅 Java", "只执行绑定摘要");
        started.command(support.request("draft/save-content", draft, Optional.of("runner-save"), 0));
        AttachmentRef attachment = new AttachmentRef(resource.digest(), resource.mediaType(), resource.id(), 128);
        support.claimAttachment(support.workspaceId, attachment);
        started.command(support.request(
                "draft/resource/add",
                new SkillContracts.AddResourceRequest("runner", resource.id(), attachment, true),
                Optional.of("runner-resource"),
                1));
        started.command(support.request(
                "publish", new SkillContracts.PublishRequest("runner", 2), Optional.of("runner-publish"), 0));
        return support.decode(
                started.command(support.request(
                        "enable", new SkillContracts.EnableRequest("runner", true), Optional.of("runner-enable"), 1)),
                SkillContracts.PublishedSkill.class);
    }

    private SkillContracts.ResourceExecutionRequest freezeResourceExecution(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            SkillContracts.Resource resource,
            TurnId turnId)
            throws Exception {
        SkillContracts.SearchResult catalog = support.decode(
                started.query(support.request(
                        "search",
                        new SkillContracts.SearchRequest("运行", 10),
                        Optional.empty(),
                        0,
                        Optional.of(turnId))),
                SkillContracts.SearchResult.class);
        SkillContracts.Summary summary = catalog.matches().getFirst();
        return new SkillContracts.ResourceExecutionRequest(
                new SkillContracts.PublishedReadRequest(summary.id(), summary.revision(), summary.digest()),
                resource.id(),
                List.of("one"));
    }

    @Test
    void memoryAndSkillPublishTypedSchemasInsteadOfOpenObjectPlaceholders() throws Exception {
        BuiltinExtensionTestSupport memorySupport = new BuiltinExtensionTestSupport();
        var memory = memorySupport.start(new MemoryExtension());
        BuiltinExtensionTestSupport skillSupport = new BuiltinExtensionTestSupport();
        var skill = skillSupport.start(new SkillExtension());

        assertTrue(memory.bundle().schemas().stream()
                .allMatch(schema -> schema.schema().json().contains("\"additionalProperties\":false")));
        assertTrue(skill.bundle().schemas().stream()
                .allMatch(schema -> schema.schema().json().contains("\"additionalProperties\":false")));
        assertTrue(tool(memory, "memory.search.tool")
                .descriptor()
                .inputSchema()
                .json()
                .contains("\"maximum\":100"));
        assertTrue(
                tool(skill, "skill.read.tool").descriptor().inputSchema().json().contains("\"digest\""));
        assertTrue(tool(skill, "skill.resource.execute.tool")
                .descriptor()
                .inputSchema()
                .json()
                .contains("\"resourceId\""));
    }

    private static MemoryContracts.CreateRequest createMemory(String id, String content) {
        return new MemoryContracts.CreateRequest(
                id, MemoryContracts.MemoryKind.FACT, "workspace", content, Set.of("v5"), false, Optional.empty());
    }

    private static MemoryContracts.UpdateRequest updateMemory(String id, String content) {
        return new MemoryContracts.UpdateRequest(
                id, MemoryContracts.MemoryKind.FACT, "workspace", content, Set.of("v5"), true, Optional.empty());
    }

    private static MemoryContracts.HistoryPage memoryHistory(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started started) throws Exception {
        return support.decode(
                started.query(support.request(
                        "history", new MemoryContracts.HistoryRequest("memory", 0, 20), Optional.empty(), 0)),
                MemoryContracts.HistoryPage.class);
    }

    private static void setLearningPolicy(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            MemoryContracts.LearningPolicy policy,
            long revision,
            String key)
            throws Exception {
        started.command(support.request(
                "settings/update", new MemoryContracts.LearningSettingsUpdate(policy), Optional.of(key), revision));
    }

    private static MemoryContracts.LearningProposalRequest proposal(
            BuiltinExtensionTestSupport support, String id, MemoryContracts.MemoryKind kind, String content) {
        MemoryContracts.Source source =
                new MemoryContracts.Source(support.workspaceId, ThreadId.random(), ItemId.random(), content);
        return new MemoryContracts.LearningProposalRequest(id, kind, "workspace", content, Set.of("v5"), source);
    }

    private static MemoryContracts.LearningResult submitLearning(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            MemoryContracts.LearningProposalRequest request,
            String key)
            throws Exception {
        return support.decode(
                started.command(support.request("proposal/submit", request, Optional.of(key), 0)),
                MemoryContracts.LearningResult.class);
    }

    private static SkillContracts.Proposal submitAndAdoptSkill(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started started) throws Exception {
        SkillContracts.ProposeRequest candidate =
                new SkillContracts.ProposeRequest("proposal-review", "review", "代码审查", "审查 Java 代码", "执行代码审查");
        started.command(support.request("proposal/submit", candidate, Optional.of("skill-propose"), 0));
        return support.decode(
                started.command(support.request(
                        "proposal/adopt",
                        new SkillContracts.ProposalDecision("proposal-review"),
                        Optional.of("skill-adopt"),
                        1)),
                SkillContracts.Proposal.class);
    }

    private static SkillContracts.SaveContentRequest draft(String instructions) {
        return new SkillContracts.SaveContentRequest("review", "代码审查", "审查 Java 代码", instructions);
    }

    private static SkillContracts.PublishedSkill publish(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            long publishedRevision,
            long draftRevision,
            String key)
            throws Exception {
        return support.decode(
                started.command(support.request(
                        "publish",
                        new SkillContracts.PublishRequest("review", draftRevision),
                        Optional.of(key),
                        publishedRevision)),
                SkillContracts.PublishedSkill.class);
    }

    private static SkillContracts.PublishedSkill enable(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            long revision,
            boolean enabled,
            String key)
            throws Exception {
        return support.decode(
                started.command(support.request(
                        "enable", new SkillContracts.EnableRequest("review", enabled), Optional.of(key), revision)),
                SkillContracts.PublishedSkill.class);
    }

    private static SkillContracts.SearchResult searchSkill(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started started, Optional<TurnId> turnId)
            throws Exception {
        return support.decode(
                started.query(support.request(
                        "search", new SkillContracts.SearchRequest("审查", 10), Optional.empty(), 0, turnId)),
                SkillContracts.SearchResult.class);
    }

    private static SkillContracts.PublishedSkill readFrozen(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            TurnId turnId,
            SkillContracts.Summary summary)
            throws Exception {
        SkillContracts.PublishedReadRequest read =
                new SkillContracts.PublishedReadRequest(summary.id(), summary.revision(), summary.digest());
        return support.decode(
                started.query(support.request("published/read", read, Optional.empty(), 0, Optional.of(turnId))),
                SkillContracts.PublishedSkill.class);
    }

    private static SkillContracts.DraftHistoryPage skillHistory(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started started) throws Exception {
        return support.decode(
                started.query(support.request(
                        "draft/history", new SkillContracts.HistoryRequest("review", 0, 20), Optional.empty(), 0)),
                SkillContracts.DraftHistoryPage.class);
    }

    private static ExtensionContributions.Tool tool(BuiltinExtensionTestSupport.Started started, String id) {
        return started.contributions().stream()
                .filter(ExtensionContributions.Tool.class::isInstance)
                .map(ExtensionContributions.Tool.class::cast)
                .filter(value -> value.contributionId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
