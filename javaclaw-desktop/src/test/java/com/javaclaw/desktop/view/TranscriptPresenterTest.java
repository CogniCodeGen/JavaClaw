package com.javaclaw.desktop.view;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptPresenterTest {
    private final CanonicalJson json = new CanonicalJson();
    private final TranscriptPresenter presenter = new TranscriptPresenter(json);

    @Test
    void mapsCoreMessageWithoutReconstructingSdkDto() {
        CorePayloads.Message message =
                new CorePayloads.Message(MessageRole.ASSISTANT, "架构已更新", List.of(), Optional.empty());

        PresentedItem presented = presenter.present(item(CoreSchemas.MESSAGE, json.encode(message)));

        assertEquals("ASSISTANT", presented.title());
        assertEquals("架构已更新", presented.body());
        assertEquals("message-assistant", presented.styleClass());
    }

    @Test
    void 虚拟化列表先绘制结果也根据完整快照中的冻结调用显示命令输出() {
        TurnId turn = TurnId.random();
        var call = new ItemEnvelope(
                ItemId.random(),
                turn,
                1,
                "tool",
                CoreSchemas.TOOL_CALL,
                "core",
                ItemStatus.COMPLETED,
                json.encode(new CorePayloads.ToolCall(
                        "run", CodingContracts.EXTENSION_ID, "command_run", 1, json.parse("{}"))),
                Instant.EPOCH,
                Optional.of(Instant.EPOCH));
        var output = new CodingResults.CommandResult(
                new CodingResults.CommandSummary(
                        "run-1", List.of("java"), ".", Optional.of(0), CodingResults.ProcessState.COMPLETED, 2),
                new CodingResults.Output("PASS", "diagnostic", 14, false));
        var result = new ItemEnvelope(
                ItemId.random(),
                turn,
                2,
                "tool",
                CoreSchemas.TOOL_RESULT,
                "core",
                ItemStatus.COMPLETED,
                json.encode(new CorePayloads.ToolResult("run", true, json.encode(output), Optional.empty())),
                Instant.EPOCH,
                Optional.of(Instant.EPOCH));
        presenter.replaceItems(List.of(call, result));
        PresentedItem shown = presenter.present(result);
        assertEquals("命令 · COMPLETED", shown.title());
        assertTrue(shown.body().contains("PASS\nstderr:\ndiagnostic"));
        presenter.replaceItems(List.of(result));
        assertEquals("工具结果", presenter.present(result).title());
    }

    @Test
    void mapsEveryCoreTranscriptPayloadAndPreservesUnknownPayload() {
        assertEquals(
                "message-user",
                present(
                                CoreSchemas.MESSAGE,
                                new CorePayloads.Message(MessageRole.USER, "请求", List.of(), Optional.empty()))
                        .styleClass());
        assertEquals(
                "transcript-execution-block",
                present(
                                CoreSchemas.MESSAGE,
                                new CorePayloads.Message(MessageRole.SYSTEM, "策略", List.of(), Optional.empty()))
                        .styleClass());
        assertEquals(
                "TOOL",
                present(
                                CoreSchemas.MESSAGE,
                                new CorePayloads.Message(MessageRole.TOOL, "结果", List.of(), Optional.of("call-1")))
                        .title());

        PresentedItem call = present(
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall("call-1", "core", "read_file", 2, new CanonicalPayload("{}")));
        assertEquals("工具 · read_file", call.title());
        assertEquals("来源 core · revision 2", call.body());

        PresentedItem success = present(
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult("call-1", true, new CanonicalPayload("{\"ok\":true}"), Optional.empty()));
        PresentedItem failure = present(
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult("call-2", false, new CanonicalPayload("{\"ok\":false}"), Optional.empty()));
        assertEquals("工具结果", success.title());
        assertEquals("工具结果 · 失败或未完成", failure.title());

        PresentedItem approval = present(
                CoreSchemas.APPROVAL,
                new CorePayloads.Approval(
                        "approval-1", "write_file", ToolRisk.EXTERNAL_EFFECT, ApprovalState.PENDING, "需要确认"));
        assertEquals("审批 · write_file", approval.title());
        assertEquals("PENDING · 需要确认", approval.body());

        PresentedItem error = present(
                CoreSchemas.ERROR, new CorePayloads.Error("MODEL_TIMEOUT", "模型超时", true, Instant.EPOCH, Map.of()));
        assertEquals("MODEL_TIMEOUT", error.title());
        assertEquals("transcript-error-block", error.styleClass());

        PresentedItem unknown = presenter.present(item("extension/custom@1", new CanonicalPayload("{\"x\":1}")));
        assertEquals("执行记录 · extension/custom@1", unknown.title());
        assertEquals("{\"x\":1}", unknown.body());
    }

    private PresentedItem present(String schemaId, com.javaclaw.api.ItemPayload payload) {
        return presenter.present(item(schemaId, json.encode(payload)));
    }

    private ItemEnvelope item(String schemaId, com.javaclaw.api.CanonicalPayload payload) {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        return new ItemEnvelope(
                ItemId.random(),
                TurnId.random(),
                1,
                "message",
                schemaId,
                "core",
                ItemStatus.COMPLETED,
                payload,
                now,
                Optional.of(now));
    }
}
