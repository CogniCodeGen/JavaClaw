package com.javaclaw.client.extension;

import java.nio.file.Path;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingTranscriptFormatterTest {
    private final CanonicalJson json = new CanonicalJson();
    private final CodingTranscriptFormatter formatter = new CodingTranscriptFormatter(json);

    @Test
    void Core命令文件事实和Coding输出均以typed文本呈现() {
        var command = formatter
                .format(item(
                        CoreSchemas.COMMAND,
                        new CorePayloads.Command("run-1", List.of("java", "Main Test"), Path.of("."), Optional.of(0))))
                .orElseThrow();
        assertTrue(command.body().contains("\"Main Test\""));
        assertTrue(command.body().contains("退出码：0"));
        var change = formatter
                .format(item(
                        CoreSchemas.FILE_CHANGE,
                        new CorePayloads.FileChange(
                                Path.of("a.txt"), "create", Optional.empty(), Optional.of("a".repeat(64)))))
                .orElseThrow();
        assertTrue(change.body().contains("a.txt"));
        var output = new CodingResults.CommandResult(
                new CodingResults.CommandSummary(
                        "run-1", List.of("java"), ".", Optional.of(1), CodingResults.ProcessState.COMPLETED, 50),
                new CodingResults.Output("\u001b[2Jtext", "error", 128, true));
        var fact = formatter.format(item(CodingResults.COMMAND_SCHEMA, output)).orElseThrow();
        assertTrue(fact.body().contains("stderr:\nerror"));
        assertTrue(fact.body().contains("下一字节游标 128"));
        assertFalse(fact.body().contains("\u001b"));
    }

    @Test
    void Diff与终端状态保留且未知schema不冒充成功() {
        var patch = new CodingResults.PatchResult(
                List.of(new CodingResults.PatchChange(
                        "a.txt",
                        "update",
                        Optional.of("a".repeat(64)),
                        Optional.of("b".repeat(64)),
                        Optional.empty(),
                        "-old\n+new")),
                true,
                Optional.empty());
        assertTrue(formatter
                .format(item(CodingResults.PATCH_SCHEMA, patch))
                .orElseThrow()
                .body()
                .contains("-old\n+new"));
        var terminal = new CodingResults.TerminalResult(
                "pty-1",
                CodingResults.ProcessState.CANCELLED,
                new CodingResults.Output("stopped", "", 7, false),
                Optional.of(130));
        assertTrue(formatter
                .format(item(CodingResults.TERMINAL_SCHEMA, terminal))
                .orElseThrow()
                .title()
                .contains("CANCELLED"));
        assertTrue(formatter
                .format(item("future/schema", new CodingContracts.TerminalClose("pty-1")))
                .isEmpty());
    }

    private ItemEnvelope item(String schema, Object payload) {
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        return new ItemEnvelope(
                ItemId.random(),
                TurnId.random(),
                1,
                "execution",
                schema,
                CodingContracts.EXTENSION_ID,
                ItemStatus.COMPLETED,
                json.encode(payload),
                now,
                Optional.of(now));
    }
}
