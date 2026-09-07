package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingTerminalPagingTest {
    @TempDir
    Path directory;

    @Test
    void oneByteTerminalPagesEmitChineseAndEmojiOnlyWhenTheirLastByteArrives() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            open(fixture);
            String content = "a中文🙂尾";
            sandbox.session.emit(content);
            int length = content.getBytes(StandardCharsets.UTF_8).length;
            var received = new StringBuilder();
            for (int offset = 0; offset < length; offset++) {
                var page = query(fixture, "terminal/output", new CodingResults.OutputRead("pty", offset, 1));
                assertEquals(offset + 1, page.output().nextOffsetBytes());
                assertEquals(offset + 1 < length, page.output().truncated());
                received.append(page.output().stdout());
            }
            assertEquals(content, received.toString());
            var empty = query(fixture, "terminal/output", new CodingResults.OutputRead("pty", length, 1));
            assertEquals("", empty.output().stdout());
            assertEquals(length, empty.output().nextOffsetBytes());
        }
    }

    @Test
    void unknownTerminalMetadataDoesNotCreateOutputBytesOrKeepAnEmptyPagePending() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        sandbox.openFailure = new IllegalStateException("native startup did not return a session");
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            assertThrows(IllegalStateException.class, () -> open(fixture));
            var output = query(fixture, "terminal/output", new CodingResults.OutputRead("pty", 0, 1));
            assertEquals(CodingResults.ProcessState.FAILED, output.state());
            assertTrue(output.exitCode().isEmpty());
            assertEquals("", output.output().stdout());
            assertEquals("", output.output().stderr());
            assertEquals(0, output.output().nextOffsetBytes());
            assertFalse(output.output().truncated());
            var metadata = query(fixture, "terminal/read", new CodingResults.ResourceRead("pty"));
            assertTrue(metadata.output().stdout().startsWith("[UNKNOWN_OUTCOME:"));
            assertEquals(0, metadata.output().nextOffsetBytes());
        }
    }

    private static void open(CodingLifecycleFixture fixture) throws Exception {
        fixture.terminals.execute(
                "terminal_open",
                fixture.invocation(
                        "pty",
                        "terminal_open",
                        new CodingContracts.TerminalOpen(
                                new CodingContracts.CommandRun(List.of("java", "-version"), ".", 10, 65_536), 80, 24)));
    }

    private static CodingResults.TerminalResult query(CodingLifecycleFixture fixture, String operation, Object input)
            throws Exception {
        var base = fixture.base;
        var request = new ExtensionRequest(
                base.workspace.id(),
                Optional.of(base.turn.threadId()),
                Optional.of(base.turn.id()),
                operation,
                base.json.encode(input),
                Optional.empty(),
                0,
                Optional.empty());
        try (var binding = base.platform.bindManagement(request, ContributionKind.QUERY, new CancellationSource())) {
            return base.json.decode(binding.invoke().payload(), CodingResults.TerminalResult.class);
        }
    }
}
