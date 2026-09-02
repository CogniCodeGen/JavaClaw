package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ToolRisk;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanProposalResourceTest {
    @Test
    void proposalAdoptionAtomicallyCreatesDefinitionAndBindsDigest() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new PlanExtension());
        PlanContracts.ProposeRequest request = new PlanContracts.ProposeRequest(
                "proposal-create", Optional.empty(), Optional.of("item-1"), plan("H2"));

        PlanContracts.Proposal pending = support.decode(
                started.command(support.request("proposal/submit", request, Optional.of("propose"), 0)),
                PlanContracts.Proposal.class);
        PlanContracts.Proposal adopted = support.decode(
                started.command(support.request(
                        "proposal/adopt",
                        new PlanContracts.ProposalDecision(pending.id()),
                        Optional.of("adopt"),
                        pending.revision())),
                PlanContracts.Proposal.class);
        PlanContracts.Definition definition = support.decode(
                started.query(
                        support.request("read", new DocumentContracts.Key("generated-plan"), Optional.empty(), 0)),
                PlanContracts.Definition.class);

        assertEquals(PlanContracts.ProposalState.PENDING, pending.state());
        assertEquals(support.payloads.encode(pending.candidate()).sha256(), pending.contentHash());
        assertEquals(PlanContracts.ProposalState.ADOPTED, adopted.state());
        assertEquals(Optional.of(1L), adopted.adoptedDefinitionRevision());
        assertEquals(pending.candidate().title(), definition.title());
        assertEquals(1, definition.revision());
    }

    @Test
    void targetRevisionChangePreventsPartialProposalAdoption() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new PlanExtension());
        started.command(support.request("definition/create", plan("H2"), Optional.of("create"), 0));
        PlanContracts.ProposeRequest request = new PlanContracts.ProposeRequest(
                "proposal-update", Optional.of(1L), Optional.of("item-2"), plan("PostgreSQL"));
        PlanContracts.Proposal pending = support.decode(
                started.command(support.request("proposal/submit", request, Optional.of("propose"), 0)),
                PlanContracts.Proposal.class);
        started.command(support.request("definition/update", plan("SQLite"), Optional.of("concurrent"), 1));

        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "proposal/adopt",
                        new PlanContracts.ProposalDecision(pending.id()),
                        Optional.of("stale-adopt"),
                        pending.revision())));
        PlanContracts.Proposal unchanged = support.decode(
                started.query(
                        support.request("proposal/read", new DocumentContracts.Key(pending.id()), Optional.empty(), 0)),
                PlanContracts.Proposal.class);
        PlanContracts.Definition current = support.decode(
                started.query(
                        support.request("read", new DocumentContracts.Key("generated-plan"), Optional.empty(), 0)),
                PlanContracts.Definition.class);
        assertEquals(PlanContracts.ProposalState.PENDING, unchanged.state());
        assertEquals(2, current.revision());
        assertEquals(
                "SQLite",
                current.openQuestions().getFirst().decision().orElseThrow().answer());
    }

    @Test
    void proposalViewOnlyOffersExplicitAdoptAndRejectActions() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new PlanExtension());
        PlanContracts.ProposeRequest request =
                new PlanContracts.ProposeRequest("proposal-view", Optional.empty(), Optional.empty(), plan("H2"));
        started.command(support.request("proposal/submit", request, Optional.of("propose"), 0));

        ViewQueryResult page = support.decode(
                started.query(support.request(
                        "proposal/view.list",
                        new ViewQueryRequest("proposals", Map.of(), "", 100, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        ViewSchema view = started.contributions().stream()
                .filter(ExtensionContributions.View.class::isInstance)
                .map(ExtensionContributions.View.class::cast)
                .filter(value -> value.contributionId().equals("plan.proposal.view"))
                .findFirst()
                .orElseThrow()
                .view();
        ViewSchema.Table table = (ViewSchema.Table) view.nodes().getFirst();

        assertEquals(1, page.rows().size());
        assertEquals(
                List.of("proposal/adopt", "proposal/reject"),
                table.actions().stream().map(action -> action.command()).toList());
        assertTrue(table.actions().stream().allMatch(action -> action.dangerous()));
    }

    @Test
    void modelToolCanOnlySubmitProposalForHumanReview() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new PlanExtension());
        ExtensionContributions.Tool tool = started.contributions().stream()
                .filter(ExtensionContributions.Tool.class::isInstance)
                .map(ExtensionContributions.Tool.class::cast)
                .filter(value -> value.contributionId().equals("plan.proposal.submit.tool"))
                .findFirst()
                .orElseThrow();
        PlanContracts.ProposeRequest request =
                new PlanContracts.ProposeRequest("proposal-tool", Optional.empty(), Optional.of("item-3"), plan("H2"));

        PlanContracts.Proposal proposal = support.decode(
                started.tool(
                        tool.contributionId(),
                        support.request(tool.contributionId(), request, Optional.of("tool-propose"), 0)),
                PlanContracts.Proposal.class);

        assertEquals("plan_proposal_submit", tool.descriptor().identity().name());
        assertEquals(ToolRisk.WORKSPACE_WRITE, tool.descriptor().risk());
        assertTrue(tool.descriptor().inputSchema().json().contains("\"additionalProperties\":false"));
        assertEquals(PlanContracts.ProposalState.PENDING, proposal.state());
        assertThrows(
                IllegalArgumentException.class,
                () -> started.query(
                        support.request("read", new DocumentContracts.Key("generated-plan"), Optional.empty(), 0)));
    }

    private static PlanContracts.ManagementSaveRequest plan(String answer) {
        return new PlanContracts.ManagementSaveRequest(
                "generated-plan",
                "生成计划",
                "验收全部通过",
                "只改 v5",
                List.of(new PlanContracts.ManagementRisk("risk", "回滚困难")),
                List.of(new PlanContracts.ManagementOpenQuestion("question", "storage", "选择存储", Optional.of(answer))),
                List.of(new PlanContracts.ManagementStep("step", "build", "构建", "执行构建", "构建成功", List.of())));
    }
}
