package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本机真实 Seatbelt/JDK 管道验收：执行未退出时管理查询已能读取持久输出。 */
@EnabledOnOs(OS.MAC)
class CodingBatchProgressNativeTest {
    @TempDir
    Path directory;

    @Test
    void 真实batch进程在退出前输出可发现且最终结果和事实保持一致() throws Exception {
        try (var fixture = new CodingTestFixture(directory);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            Path source = fixture.root.resolve("StreamingProbe.java");
            Files.writeString(source, """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public final class StreamingProbe {
                    public static void main(String[] args) throws Exception {
                        System.out.print("started");
                        System.out.flush();
                        while (!Files.exists(Path.of(args[0]))) {
                            Thread.sleep(10);
                        }
                        System.err.print("done");
                        System.err.flush();
                    }
                }
                """);
            assertEquals(
                    0,
                    ToolProvider.getSystemJavaCompiler()
                            .run(null, null, null, "-d", fixture.root.toString(), source.toString()));
            Path release = fixture.root.resolve("release");
            var input = new CodingContracts.CommandRun(
                    List.of("java", "-cp", fixture.root.toString(), "StreamingProbe", release.toString()),
                    ".",
                    20,
                    65_536);
            var run = tasks.submit(() -> fixture.invoke("command_run", input, "native-progress"));
            CodingResults.ExecutionSummary observed;
            try {
                observed = awaitOutput(fixture);
                assertEquals("RUNNING", observed.state());
                assertFalse(run.isDone());
                var first = fixture.json.decode(
                        query(fixture, "command/output", new CodingResults.OutputRead(observed.operationId(), 0, 100)),
                        CodingResults.Output.class);
                assertEquals("started", first.stdout());
                assertEquals(7, first.nextOffsetBytes());
            } finally {
                Files.writeString(release, "release");
            }
            var outcome = run.get(10, TimeUnit.SECONDS);
            assertTrue(outcome.success());
            var command = fixture.json.decode(outcome.response().payload(), CodingResults.CommandResult.class);
            assertEquals("started", command.output().stdout());
            assertEquals("done", command.output().stderr());
            assertEquals(1, outcome.facts().size());
            var last = fixture.json.decode(
                    query(fixture, "command/output", new CodingResults.OutputRead(observed.operationId(), 7, 100)),
                    CodingResults.Output.class);
            assertEquals("done", last.stderr());
            assertEquals(11, last.nextOffsetBytes());
        }
    }

    private static CodingResults.ExecutionSummary awaitOutput(CodingTestFixture fixture) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var list = fixture.json.decode(
                    query(fixture, "execution/list", new CodingEnvironmentContracts.Empty()),
                    CodingResults.ExecutionList.class);
            if (!list.executions().isEmpty() && list.executions().getFirst().outputBytes() > 0) {
                return list.executions().getFirst();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("真实进程在退出前未提交输出");
    }

    private static com.javaclaw.api.CanonicalPayload query(CodingTestFixture fixture, String name, Object payload)
            throws Exception {
        var request = new ExtensionRequest(
                fixture.workspace.id(),
                Optional.of(fixture.turn.threadId()),
                Optional.of(fixture.turn.id()),
                name,
                fixture.json.encode(payload),
                Optional.empty(),
                0,
                Optional.empty());
        try (var binding = fixture.platform.bindManagement(request, ContributionKind.QUERY, new CancellationSource())) {
            return binding.invoke().payload();
        }
    }
}
