package com.javaclaw.client.extension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingToolResultTranscriptTest {
    private final CanonicalJson json = new CanonicalJson();
    private final CodingTranscriptFormatter formatter = new CodingTranscriptFormatter(json);
    private final CodingToolResultIndex index = new CodingToolResultIndex(json);
    private final TurnId turn = TurnId.random();

    @Test
    void 跨页保留冻结调用关联并显示真实命令输出和Diff恢复路径() {
        var call = call(turn, "run", "command_run", CodingContracts.EXTENSION_ID, 1);
        index.accept(call);
        index.accept(call);
        var result = result(turn, "run", command(), true);
        var text = formatter.format(result, index.callFor(result)).orElseThrow();
        assertTrue(text.body().contains("PASS\nstderr:\nwarning"));
        var patchCall = call(turn, "patch", "file_apply_patch", CodingContracts.EXTENSION_ID, 1);
        index.accept(patchCall);
        var patch = new CodingResults.PatchResult(
                List.of(new CodingResults.PatchChange(
                        "a.txt",
                        "update",
                        Optional.of("a".repeat(64)),
                        Optional.of("b".repeat(64)),
                        Optional.empty(),
                        "-old\n+new")),
                false,
                Optional.of("PATCH_CONFLICT"),
                List.of(".javaclaw-recovery/keep"));
        var patchResult = result(turn, "patch", patch, false);
        var diff = formatter.format(patchResult, index.callFor(patchResult)).orElseThrow();
        assertTrue(diff.body().contains("-old\n+new"));
        assertTrue(diff.body().contains(".javaclaw-recovery/keep"));
    }

    @Test
    void 缺关联跨Turn未知生产者或工具版本都不能按JSON外形冒充Coding() {
        var result = result(turn, "run", command(), true);
        assertTrue(formatter.format(result, Optional.empty()).isEmpty());
        assertTrue(formatter
                .format(
                        result,
                        Optional.of(call(TurnId.random(), "run", "command_run", CodingContracts.EXTENSION_ID, 1)))
                .isEmpty());
        assertTrue(formatter
                .format(result, Optional.of(call(turn, "run", "command_run", "other.extension", 1)))
                .isEmpty());
        assertTrue(formatter
                .format(result, Optional.of(call(turn, "run", "command_run", CodingContracts.EXTENSION_ID, 2)))
                .isEmpty());
        assertTrue(formatter
                .format(result, Optional.of(call(turn, "run", "future_command", CodingContracts.EXTENSION_ID, 1)))
                .isEmpty());
        var invalid = result(turn, "run", new CodingContracts.TerminalClose("not-a-command-result"), true);
        assertTrue(formatter
                .format(invalid, Optional.of(call(turn, "run", "command_run", CodingContracts.EXTENSION_ID, 1)))
                .isEmpty());
    }

    @Test
    void 重复身份冲突和会话重置取消关联() {
        index.accept(call(turn, "run", "command_run", CodingContracts.EXTENSION_ID, 1));
        index.accept(call(turn, "run", "file_read", CodingContracts.EXTENSION_ID, 1));
        var result = result(turn, "run", command(), true);
        assertTrue(index.callFor(result).isEmpty());
        index.clear();
        index.accept(call(turn, "run", "command_run", CodingContracts.EXTENSION_ID, 1));
        assertTrue(index.callFor(result).isPresent());
        assertTrue(
                index.callFor(result(TurnId.random(), "run", command(), true)).isEmpty());
        index.clear();
        assertTrue(index.callFor(result).isEmpty());
    }

    @Test
    void 已知工具结构化失败按失败契约显示且不冒充成功() {
        var call = call(turn, "run", "command_run", CodingContracts.EXTENSION_ID, 1);
        var failure = new CodingResults.Failure("MISSING_TOOLCHAIN", "请安装工具链", "op-1", true);
        var result = result(turn, "run", failure, false);
        var fact = formatter.format(result, Optional.of(call)).orElseThrow();
        assertEquals("执行失败 · MISSING_TOOLCHAIN", fact.title());
        assertTrue(formatter
                .format(result(turn, "run", failure, true), Optional.of(call))
                .isEmpty());
    }

    @Test
    void 未知Coding修订回退保留原始正文且有界而不按旧契约解释() {
        var value = result(turn, "future", java.util.Map.of("future", "x".repeat(20000)), true);
        var future = call(turn, "future", "command_run", CodingContracts.EXTENSION_ID, 2);
        assertTrue(formatter.format(value, Optional.of(future)).isEmpty());
        var fallback = formatter.fallback(value);
        assertTrue(fallback.body().startsWith("{\"future\":"));
        assertTrue(fallback.body().length() < 16500);
        assertTrue(fallback.body().contains("截断"));
        assertEquals("a�[31mb�31mc", new CodingTranscriptFormatter.Fact("", "a\u001b[31mb\u009b31mc").body());
    }

    private ItemEnvelope call(TurnId owner, String id, String name, String producer, long revision) {
        return item(
                owner,
                1,
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall(id, producer, name, revision, json.parse("{}")));
    }

    private ItemEnvelope result(TurnId owner, String id, Object value, boolean success) {
        return item(
                owner,
                2,
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(id, success, json.encode(value), Optional.empty()));
    }

    private ItemEnvelope item(TurnId owner, long sequence, String schema, Object payload) {
        return new ItemEnvelope(
                ItemId.random(),
                owner,
                sequence,
                "tool",
                schema,
                "core",
                ItemStatus.COMPLETED,
                json.encode(payload),
                Instant.EPOCH,
                Optional.of(Instant.EPOCH));
    }

    private static CodingResults.CommandResult command() {
        return new CodingResults.CommandResult(
                new CodingResults.CommandSummary(
                        "run-1", List.of("java"), ".", Optional.of(0), CodingResults.ProcessState.COMPLETED, 20),
                new CodingResults.Output("PASS", "warning", 11, false));
    }
}
