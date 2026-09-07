package com.javaclaw.server.coding;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定模型只决定下一步；摘要、命令退出码及输出均来自真实工具回填。 */
final class CodingHarnessModel implements ModelGateway {
    static final String INITIAL_CHAT = "我可以查看项目、修改代码并运行测试。";
    static final String FINAL_CHAT = "已修复并通过测试。";
    static final String FILE_NAME = "ProbeTest.java";
    static final String GOOD_SOURCE = """
            class ProbeTest {
                public static void main(String[] args) {
                    int expected = 4;
                    if (2 + 2 != expected) {
                        throw new AssertionError("bad expectation");
                    }
                    System.out.println("PASS");
                }
            }
            """;
    private static final String BAD_SOURCE = GOOD_SOURCE.replace("expected = 4", "expected = 5");
    private final CanonicalJson json = new CanonicalJson();
    private int invocations;
    private String brokenDigest;
    CodingResults.CommandResult failedCommand;
    CodingResults.CommandResult successfulCommand;

    @Override
    public ModelCapabilities capabilities(String modelId) {
        return new ModelCapabilities(false, true, false, false, false, false, false);
    }

    @Override
    public ModelInvocationResult invoke(
            TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return switch (invocations++) {
            case 0 -> result(INITIAL_CHAT, List.of());
            case 1 -> discover(invocation);
            case 2 -> call("list", "file_list", new CodingContracts.FileList(".", Optional.empty(), 100));
            case 3 -> read(invocation);
            case 4 -> breakTest(invocation);
            case 5 -> runBrokenTest(invocation);
            case 6 -> repair(invocation);
            case 7 -> runRepairedTest(invocation);
            case 8 -> complete(invocation);
            default -> throw new AssertionError("unexpected model invocation");
        };
    }

    private ModelInvocationResult discover(ModelInvocation invocation) {
        assertTrue(invocation.messages().stream().anyMatch(message -> INITIAL_CHAT.equals(message.text())));
        return result(
                "",
                List.of(new ModelToolCall(
                        "coding-search",
                        CoreTools.search().identity(),
                        json.encode(Map.of("query", "coding", "limit", 20)))));
    }

    private ModelInvocationResult read(ModelInvocation invocation) {
        var listing = output(invocation, "list", CodingResults.FileListResult.class);
        assertTrue(listing.entries().stream().anyMatch(entry -> FILE_NAME.equals(entry.path())));
        return call("read", "file_read", new CodingContracts.FileRead(FILE_NAME, 0, 65_536));
    }

    private ModelInvocationResult breakTest(ModelInvocation invocation) {
        var read = output(invocation, "read", CodingResults.FileReadResult.class);
        assertEquals(GOOD_SOURCE, read.text());
        return patch("break", read.sha256(), BAD_SOURCE);
    }

    private ModelInvocationResult runBrokenTest(ModelInvocation invocation) {
        var changed = output(invocation, "break", CodingResults.PatchResult.class);
        assertTrue(changed.complete());
        brokenDigest = changed.changes().getFirst().afterSha256().orElseThrow();
        return command("failure");
    }

    private ModelInvocationResult repair(ModelInvocation invocation) {
        failedCommand = output(invocation, "failure", CodingResults.CommandResult.class);
        assertEquals(Optional.of(1), failedCommand.command().exitCode());
        assertTrue(failedCommand.output().stderr().contains("bad expectation"));
        return patch("repair", brokenDigest, GOOD_SOURCE);
    }

    private ModelInvocationResult runRepairedTest(ModelInvocation invocation) {
        var changed = output(invocation, "repair", CodingResults.PatchResult.class);
        assertTrue(changed.complete());
        assertEquals(Optional.of(brokenDigest), changed.changes().getFirst().beforeSha256());
        return command("success");
    }

    private ModelInvocationResult complete(ModelInvocation invocation) {
        successfulCommand = output(invocation, "success", CodingResults.CommandResult.class);
        assertEquals(Optional.of(0), successfulCommand.command().exitCode());
        assertTrue(successfulCommand.output().stdout().contains("PASS"));
        return result(FINAL_CHAT, List.of());
    }

    private ModelInvocationResult patch(String id, String digest, String content) {
        return call(
                id,
                "file_apply_patch",
                new CodingContracts.ApplyPatch(List.of(new CodingContracts.FileEdit(
                        FILE_NAME, Optional.of(digest), Optional.of(content), Optional.empty()))));
    }

    private ModelInvocationResult command(String id) {
        return call(id, "command_run", new CodingContracts.CommandRun(List.of("java", FILE_NAME), ".", 30, 65_536));
    }

    private ModelInvocationResult call(String id, String tool, Object arguments) {
        return result(
                "",
                List.of(new ModelToolCall(
                        id,
                        new ToolIdentity(CodingContracts.EXTENSION_ID, tool, CodingContracts.REVISION),
                        json.encode(arguments))));
    }

    private <T> T output(ModelInvocation invocation, String callId, Class<T> type) {
        var message = invocation.messages().stream()
                .filter(value ->
                        value.role() == MessageRole.TOOL && value.toolCallId().equals(Optional.of(callId)))
                .reduce((first, last) -> last)
                .orElseThrow();
        return json.decode(json.parse(message.text()), type);
    }

    private static ModelInvocationResult result(String text, List<ModelToolCall> calls) {
        return new ModelInvocationResult(
                text,
                calls,
                new ModelUsage(10, 2, 0, 0),
                Optional.empty(),
                Optional.empty(),
                calls.isEmpty() ? ModelFinishReason.COMPLETE : ModelFinishReason.TOOL_CALLS);
    }
}
