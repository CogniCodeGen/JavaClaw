package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts;
import com.javaclaw.builtin.contracts.CodingScriptContracts;
import com.javaclaw.builtin.contracts.CodingSystemContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定模型通过真实冻结目录、Host、原生执行器和事务日志使用新增工具；不调用付费模型。 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class LocalToolsHarnessIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void 同一Turn提交目录二进制与三类进程事实及回执() throws Exception {
        try (var fixture = new CodingHarnessFixture(directory, new LocalModel())) {
            var command = fixture.queued("local-tools", "创建二进制文件，执行Java片段、系统程序与Shell。");
            var result = fixture.harness.execute(command, new CancellationSource());
            assertEquals(
                    TurnStatus.COMPLETED, result.status(), result.errorCode().toString());
            assertEquals("已完成本地工具验证", result.assistantText());
            assertEquals(6, result.toolCalls());
            assertArrayEquals(
                    new byte[] {0, 1, (byte) 255}, Files.readAllBytes(fixture.base.root.resolve("data/a.bin")));
            assertEquals("shell-result", Files.readString(fixture.base.root.resolve("shell.txt")));
            var items = fixture.base.core.listItems(fixture.thread.id());
            assertEquals(
                    1,
                    items.stream()
                            .filter(item -> item.schemaId().equals(CoreSchemas.DIRECTORY_CHANGE))
                            .count());
            assertEquals(
                    1,
                    items.stream()
                            .filter(item -> item.schemaId().equals(CoreSchemas.FILE_CHANGE))
                            .count());
            assertEquals(
                    3,
                    items.stream()
                            .filter(item -> item.schemaId().equals(CoreSchemas.COMMAND))
                            .count());
            assertEquals(
                    5,
                    items.stream()
                            .filter(item -> item.schemaId().equals(CoreSchemas.EFFECT_RECEIPT))
                            .count());
            items.stream()
                    .filter(item -> item.schemaId().equals(CoreSchemas.TOOL_RESULT))
                    .forEach(item -> {
                        var tool = fixture.base.json.decode(item.payload(), CorePayloads.ToolResult.class);
                        assertTrue(tool.success(), tool.output().json());
                        tool.receipt()
                                .ifPresent(receipt -> assertEquals(tool.output().sha256(), receipt.resultDigest()));
                    });
            var checkpoint = fixture.journal.readRecovery(command.turn().id());
            assertEquals(6, checkpoint.toolCalls());
            assertTrue(checkpoint.activeIntentDigest().isEmpty());
        }
    }

    private static final class LocalModel implements ModelGateway {
        private final CanonicalJson json = new CanonicalJson();
        private int step;

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, true, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turn, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            // 检查真实工具回填，不能用替身输出掩盖原生执行或事务失败。
            invocation.messages().stream()
                    .filter(message -> message.role() == MessageRole.TOOL)
                    .forEach(message -> {
                        var payload = json.parse(message.text());
                        assertFalse(json.fieldNames(payload).contains("errorCode"), message.text());
                    });
            return switch (step++) {
                case 0 ->
                    result(List.of(new ModelToolCall(
                            "discover",
                            CoreTools.search().identity(),
                            json.encode(Map.of("query", "coding", "limit", 50)))));
                case 1 -> call("mkdir", "file_mkdir", new CodingFileSystemContracts.FileMkdir("data", false));
                case 2 ->
                    call(
                            "binary",
                            "file_write",
                            new CodingFileSystemContracts.FileWrite(
                                    "data/a.bin", "AAH/", CodingFileSystemContracts.Encoding.BASE64, Optional.empty()));
                case 3 ->
                    call("java", "script_run", new CodingScriptContracts.ScriptRun("int n = 2; n + 3;", ".", 30, 4096));
                case 4 ->
                    call(
                            "program",
                            "system_command_run",
                            new CodingSystemContracts.CommandRun(
                                    "system.echo", List.of("argv literal | >"), ".", 30, 4096));
                case 5 ->
                    call(
                            "shell",
                            "system_shell_run",
                            new CodingSystemContracts.ShellRun(
                                    "printf '%s' 'shell-result' > shell.txt", ".", 30, 4096));
                case 6 -> result(List.of());
                default -> throw new AssertionError("unexpected model invocation");
            };
        }

        private ModelInvocationResult call(String id, String name, Object arguments) {
            return result(List.of(new ModelToolCall(
                    id,
                    new ToolIdentity(CodingContracts.EXTENSION_ID, name, CodingContracts.REVISION),
                    json.encode(arguments))));
        }

        private static ModelInvocationResult result(List<ModelToolCall> calls) {
            return new ModelInvocationResult(
                    calls.isEmpty() ? "已完成本地工具验证" : "",
                    calls,
                    new ModelUsage(10, 2, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    calls.isEmpty() ? ModelFinishReason.COMPLETE : ModelFinishReason.TOOL_CALLS);
        }
    }
}
