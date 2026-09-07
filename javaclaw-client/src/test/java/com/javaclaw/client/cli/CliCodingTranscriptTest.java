package com.javaclaw.client.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CoreRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliCodingTranscriptTest {
    @Test
    void 分次轮询真实Core结果显示命令输出且重复读取不重印() throws Exception {
        var peer = new CliTestPeer();
        var call = item(
                1,
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall(
                        "run", CodingContracts.EXTENSION_ID, "command_run", 1, CliTestPeer.JSON.parse("{}")));
        var command = new CodingResults.CommandResult(
                new CodingResults.CommandSummary(
                        "run-1", List.of("java"), ".", Optional.of(0), CodingResults.ProcessState.COMPLETED, 5),
                new CodingResults.Output("PASS", "warn", 8, false));
        var result = item(
                2,
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult("run", true, CliTestPeer.JSON.encode(command), Optional.empty()));
        peer.onItems = query -> query.afterSequence() == 0
                ? new CoreRpcContracts.ItemListResult(List.of(call), 1)
                : new CoreRpcContracts.ItemListResult(List.of(result), 2);
        var bytes = new ByteArrayOutputStream();
        try (var client = peer.connect()) {
            var messages = new CliTurnMessages(client, new PrintStream(bytes));
            messages.read(peer.current);
            messages.read(peer.current);
            String printed = bytes.toString();
            assertTrue(printed.contains("PASS\nstderr:\nwarn"));
            peer.onItems = query -> new CoreRpcContracts.ItemListResult(List.of(), query.afterSequence());
            messages.read(peer.current);
            assertEquals(printed, bytes.toString());
        }
    }

    private static ItemEnvelope item(long sequence, String schema, Object payload) {
        return new ItemEnvelope(
                ItemId.random(),
                CliTestPeer.TURN,
                sequence,
                "tool",
                schema,
                "core",
                ItemStatus.COMPLETED,
                CliTestPeer.JSON.encode(payload),
                CliTestPeer.NOW,
                Optional.of(CliTestPeer.NOW));
    }
}
