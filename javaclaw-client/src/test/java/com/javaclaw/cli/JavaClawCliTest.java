package com.javaclaw.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.TurnInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaClawCliTest {
    @Test
    void parsesBooleanOutputAndStreamingFlagsWithoutValues() {
        JavaClawCli.Arguments arguments =
                JavaClawCli.Arguments.parse(new String[] {"--json", "run", "--no-stream", "--prompt", "hello"});

        assertEquals("run", arguments.command);
        assertTrue(arguments.json);
        assertTrue(arguments.noStream);
        assertEquals("hello", arguments.required("prompt"));
    }

    @Test
    void usageAndParseFailuresHaveStableExitCodes() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            assertEquals(JavaClawCli.EXIT_OK, JavaClawCli.execute(new String[] {"--help"}, out, err));
            assertEquals(
                    JavaClawCli.EXIT_USAGE,
                    JavaClawCli.execute(new String[] {"--json", "workspace-list", "--server-classpath"}, out, err));
        }

        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("Stable exit codes"));
        String error = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(error.contains("\"exitCode\":2"));
        assertFalse(error.contains("Exception"));
    }

    @Test
    void humanRendererProducesReadableNestedOutput() {
        var root = Map.of("state", "COMPLETED", "items", List.of(Map.of("kind", "agentMessage", "text", "hello")));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            JavaClawCli.renderHuman(root, 0, out);
        }

        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("state: COMPLETED"));
        assertTrue(rendered.contains("kind: agentMessage"));
        assertTrue(rendered.contains("text: hello"));
    }

    @Test
    void completedTransportDoesNotHideFailedOrInterruptedTurn() {
        var args = JavaClawCli.Arguments.parse(new String[] {"run"});
        for (var status :
                Map.of("COMPLETED", 0, "FAILED", 7, "INTERRUPTED", 130).entrySet()) {
            var selected = turn("selected", status.getKey());
            // 同一 Thread 的其他成功 Turn 不能掩盖本次失败；必须按接受响应的 id 选择终态。
            var snapshot = new ThreadSnapshot(null, List.of(turn("earlier", "COMPLETED"), selected), List.of());
            assertEquals(
                    status.getValue(),
                    JavaClawCli.commandExitCode(args, Map.of("acceptedTurn", selected, "transcript", snapshot)));
            var wait = JavaClawCli.Arguments.parse(new String[] {"turn-await", "--turn", "selected"});
            assertEquals(status.getValue(), JavaClawCli.commandExitCode(wait, snapshot));
        }
    }

    @Test
    void nestedAsyncTimeoutKeepsStableTimeoutExitCode() {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            assertEquals(
                    JavaClawCli.EXIT_TIMEOUT,
                    JavaClawCli.writeAsyncFailure(
                            true,
                            new CompletionException(
                                    new ExecutionException(new TimeoutException("Turn wait timed out"))),
                            output));
        }
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("\"exitCode\":5"));
    }

    private static TurnInfo turn(String id, String status) {
        return new TurnInfo(
                id,
                "thread",
                status,
                "attempt",
                List.of(),
                new JsonDocument("{}"),
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1));
    }
}
