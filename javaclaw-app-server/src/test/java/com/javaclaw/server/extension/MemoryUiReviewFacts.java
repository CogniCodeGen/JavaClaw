package com.javaclaw.server.extension;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;

/** UI 复审使用明确标注的持久执行夹具，不调用真实工具、不产生外部副作用。 */
final class MemoryUiReviewFacts {
    private MemoryUiReviewFacts() {}

    static void learning(MemoryIntegrationFixture fixture, Workspace workspace, AgentTurn reference) {
        var execution = new ExecutionOverrides(
                Optional.of(reference.role()),
                Optional.of(reference.provider()),
                Optional.of(reference.permissionProfile()),
                Optional.of(ApprovalPolicy.EVERY_CALL),
                Optional.empty(),
                Optional.empty(),
                Optional.of(ReasoningPreference.HIGH));
        fixture.command(
                workspace,
                BuiltinExtensionIds.MEMORY,
                "learning/save",
                new MemoryV3Contracts.LearningSave(true, execution, false),
                0,
                MemoryV3Contracts.LearningDefinition.class);
    }

    static void history(MemoryIntegrationFixture fixture, Workspace workspace) throws Exception {
        var turn = fixture.conversation(workspace, "历史执行记录验收");
        var json = fixture.components.json();
        var journal = new H2TurnJournal(
                new H2Database(fixture.dataRoot), CoreItemCodecs.createRegistry(json), json, Clock.systemUTC());
        journal.append(
                turn.id(),
                "tool-call",
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall("fixture-success", "acceptance-fixture", "fixture_read", 1, json.parse("{}")),
                ItemStatus.COMPLETED);
        journal.append(
                turn.id(),
                "tool-result",
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(
                        "fixture-success", true, json.encode(Map.of("message", "持久工具结果验收：读取完成")), Optional.empty()),
                ItemStatus.COMPLETED);
        journal.append(
                turn.id(),
                "tool-result",
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(
                        "fixture-failure",
                        false,
                        json.encode(Map.of("message", "持久工具结果验收：拒绝读取", "success", true)),
                        Optional.empty()),
                ItemStatus.FAILED);
        journal.append(
                turn.id(),
                "error",
                CoreSchemas.ERROR,
                new CorePayloads.Error("UI_ACCEPTANCE_DENIED", "历史错误说明：测试拒绝访问", false, Instant.now(), Map.of()),
                ItemStatus.FAILED);
    }
}
