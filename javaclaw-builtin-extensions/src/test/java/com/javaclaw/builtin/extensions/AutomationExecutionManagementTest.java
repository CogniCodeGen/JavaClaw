package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationExecutionManagementTest {
    @Test
    void viewRequiresAuthoritativeDefinitionAndProfileSelections() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new PlanExtension());
        started.command(support.request("definition/create", plan(), Optional.of("put"), 0));
        ViewSchema view = started.contributions().stream()
                .filter(ExtensionContributions.View.class::isInstance)
                .map(ExtensionContributions.View.class::cast)
                .filter(candidate -> candidate.contributionId().equals("execution.view"))
                .findFirst()
                .orElseThrow()
                .view();

        assertEquals(
                List.of("documents", "executionDefinition", "profiles", "executions"),
                view.dataSources().stream().map(source -> source.id()).toList());
        ViewSchema.Form form =
                assertInstanceOf(ViewSchema.Form.class, view.nodes().get(2));
        assertEquals(
                List.of("definitionId", "profileId", "profileRevision"),
                form.submit().commandBindings().stream()
                        .map(binding -> binding.argumentName())
                        .toList());
        assertFalse(form.fields().stream().anyMatch(field -> field.name().contains("profile")));
        assertFalse(form.fields().stream().anyMatch(field -> field.name().contains("definition")));

        ViewQueryResult profiles = support.decode(
                started.query(support.request(
                        "execution/profile/view.list", query("profiles", Map.of()), Optional.empty(), 0)),
                ViewQueryResult.class);
        assertEquals(1, profiles.rows().size());

        ViewQueryResult selected = support.decode(
                started.query(support.request(
                        "execution/definition/view.selected",
                        query("executionDefinition", Map.of("id", "plan", "revision", "1")),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        assertEquals(1, selected.revision());
        assertEquals(
                "plan", support.payloads.decode(selected.values(), Map.class).get("id"));
    }

    @Test
    void flatManagementStartQueuesExactFrozenExecution() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new PlanExtension());
        started.command(support.request("definition/create", plan(), Optional.of("put"), 0));
        var input = new OrchestrationContracts.ManagementStartRequest("plan", "profile", 1, 10, 10_000, 5_000, 50);

        var response =
                started.orchestrate(support.request("execution/management/start", input, Optional.of("start"), 1));
        ExtensionExecutionReceipt receipt = publicReceipt(support, response);
        ExtensionJob job = support.jobs.find(receipt.id()).orElseThrow();

        assertEquals(ExecutionState.QUEUED, receipt.state());
        assertEquals(1, receipt.definitionRevision());
        assertTrue(support.turns.commands().isEmpty());
        OrchestrationContracts.FrozenExecution frozen =
                support.payloads.decode(job.frozenInput(), OrchestrationContracts.FrozenExecution.class);
        assertEquals("profile", frozen.platform().profile().id());
        assertEquals(10, frozen.budget().maximumTurns());
        ViewQueryResult executions = support.decode(
                started.query(
                        support.request("execution/view.list", query("executions", Map.of()), Optional.empty(), 0)),
                ViewQueryResult.class);
        Map<?, ?> row = support.payloads.decode(executions.rows().getFirst(), Map.class);
        assertEquals(java.util.Set.of("id", "state", "definitionRevision", "revision", "updatedAt"), row.keySet());
        assertFalse(row.containsKey("frozenInput"));
        assertFalse(row.containsKey("checkpoint"));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.orchestrate(
                        support.request("execution/management/start", input, Optional.of("stale-definition"), 0)));
    }

    private static ViewQueryRequest query(String source, Map<String, String> arguments) {
        return new ViewQueryRequest(source, arguments, "", 100, Optional.empty());
    }

    private static ExtensionExecutionReceipt publicReceipt(
            BuiltinExtensionTestSupport support, com.javaclaw.extension.spi.ExtensionResponse response) {
        Map<?, ?> payload = support.payloads.decode(response.payload(), Map.class);
        assertEquals(
                Set.of(
                        "id",
                        "extensionId",
                        "workspaceId",
                        "jobType",
                        "definitionId",
                        "definitionRevision",
                        "state",
                        "revision",
                        "errorCode",
                        "createdAt",
                        "updatedAt"),
                payload.keySet());
        assertFalse(payload.containsKey("frozenInput"));
        assertFalse(payload.containsKey("checkpoint"));
        return support.decode(response, ExtensionExecutionReceipt.class);
    }

    private static PlanContracts.ManagementSaveRequest plan() {
        return new PlanContracts.ManagementSaveRequest(
                "plan",
                "发布计划",
                "完成发布",
                "当前 Workspace",
                List.of(new PlanContracts.ManagementRisk("risk", "发布失败")),
                List.of(),
                List.of(new PlanContracts.ManagementStep("step", "release", "发布", "执行发布", "发布命令成功", List.of())));
    }
}
