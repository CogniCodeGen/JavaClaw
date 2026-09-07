package com.javaclaw.server.coding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;
import com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess;
import com.javaclaw.server.persistence.CodingCommandStreamRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 控制进程替身只控制退出时序，输出、管理查询和租约仍经过真实 Server/H2 链。 */
class CodingBatchProgressTest {
    @TempDir
    Path directory;

    @Test
    void batch尚未返回时可发现操作并读取已提交输出最终仍保存原始结果() throws Exception {
        var sandbox = new ObservedSandbox(false, false);
        try (var fixture = new CodingLifecycleFixture(directory, sandbox);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var invocation = fixture.invocation("progress", "command_run", command());
            var run = tasks.submit(() -> fixture.processes.run(invocation));
            try {
                assertTrue(sandbox.observed.await(5, TimeUnit.SECONDS));
                assertFalse(run.isDone());
                var list = fixture.base.json.decode(
                        query(fixture, "execution/list", new CodingEnvironmentContracts.Empty()),
                        CodingResults.ExecutionList.class);
                assertEquals("progress", list.executions().getFirst().operationId());
                assertEquals(5, list.executions().getFirst().outputBytes());
                var first = fixture.base.json.decode(
                        query(fixture, "command/output", new CodingResults.OutputRead("progress", 0, 100)),
                        CodingResults.Output.class);
                assertEquals("first", first.stdout());
                assertEquals(5, first.nextOffsetBytes());
            } finally {
                sandbox.release.countDown();
            }
            var result = run.get(5, TimeUnit.SECONDS);
            var command = (CodingResults.CommandResult) result.value();
            assertEquals("first", command.output().stdout());
            assertEquals("tail", command.output().stderr());
            assertEquals(1, fixture.toolchains.released.get());
            var output = new com.javaclaw.server.persistence.CodingCommandOutputRepository(
                            fixture.base.database, fixture.base.json)
                    .read(fixture.base.workspace.id(), "progress");
            assertEquals(9, output.stdoutBytes() + output.stderrBytes());
            assertEquals(1, result.facts().size());
        }
    }

    @Test
    void 观察输出后原生失败保留片段和未知状态且不生成成功退出证据() throws Exception {
        var sandbox = new ObservedSandbox(true, false);
        sandbox.release.countDown();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            var invocation = fixture.invocation("failed-stream", "command_run", command());
            assertThrows(IOException.class, () -> fixture.processes.run(invocation));
            assertEquals(1, fixture.toolchains.released.get());
            fixture.records.recoverInterrupted();
            var list = fixture.base.json.decode(
                    query(fixture, "execution/list", new CodingEnvironmentContracts.Empty()),
                    CodingResults.ExecutionList.class);
            assertEquals("UNKNOWN_OUTCOME", list.executions().getFirst().state());
            assertTrue(list.executions().getFirst().exitCode().isEmpty());
            var page = fixture.base.json.decode(
                    query(fixture, "command/output", new CodingResults.OutputRead("failed-stream", 0, 100)),
                    CodingResults.Output.class);
            assertEquals("first", page.stdout());
            assertEquals(5, page.nextOffsetBytes());
        }
    }

    @Test
    void 最终结果与观察流不符会失败且不能把不完整流封为成功() throws Exception {
        var sandbox = new ObservedSandbox(false, true);
        sandbox.release.countDown();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            var invocation = fixture.invocation("mismatch", "command_run", command());
            assertThrows(IllegalStateException.class, () -> fixture.processes.run(invocation));
            var attachments = new com.javaclaw.server.persistence.AttachmentService(
                    fixture.base.database, fixture.base.json, fixture.base.clock);
            var stream = new CodingCommandStreamRepository(fixture.base.database, attachments, fixture.base.json)
                    .find(fixture.base.workspace.id(), fixture.base.turn.id(), "mismatch")
                    .orElseThrow();
            assertEquals("RUNNING", stream.state());
            assertTrue(stream.exitCode().isEmpty());
            assertEquals(9, stream.outputBytes());
            assertEquals(1, fixture.toolchains.released.get());
        }
    }

    private static com.javaclaw.api.CanonicalPayload query(CodingLifecycleFixture fixture, String name, Object payload)
            throws Exception {
        var request = new ExtensionRequest(
                fixture.base.workspace.id(),
                Optional.of(fixture.base.turn.threadId()),
                Optional.of(fixture.base.turn.id()),
                name,
                fixture.base.json.encode(payload),
                Optional.empty(),
                0,
                Optional.empty());
        try (var binding =
                fixture.base.platform.bindManagement(request, ContributionKind.QUERY, new CancellationSource())) {
            return binding.invoke().payload();
        }
    }

    private static CodingContracts.CommandRun command() {
        return new CodingContracts.CommandRun(List.of("java", "Main"), ".", 10, 100);
    }

    private static final class ObservedSandbox implements CodingProcessSandbox {
        private final CountDownLatch observed = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean fail;
        private final boolean mismatch;

        private ObservedSandbox(boolean fail, boolean mismatch) {
            this.fail = fail;
            this.mismatch = mismatch;
        }

        @Override
        public SandboxResult execute(
                SandboxCommand command,
                PermissionProfile permission,
                CancellationToken cancellation,
                SandboxRuntimeAccess access,
                SandboxNetworkAccess network) {
            throw new AssertionError("生产管理器必须选择带观察器的执行入口");
        }

        @Override
        public SandboxResult execute(
                SandboxCommand command,
                PermissionProfile permission,
                CancellationToken cancellation,
                SandboxRuntimeAccess access,
                SandboxNetworkAccess network,
                Consumer<SandboxFrame> observer)
                throws Exception {
            observer.accept(new SandboxFrame("stdout", "first".getBytes(StandardCharsets.UTF_8), Instant.now()));
            observed.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IOException("测试未释放进程");
            }
            if (fail) {
                throw new IOException("原生输出观察后执行失败");
            }
            observer.accept(new SandboxFrame("stderr", "tail".getBytes(StandardCharsets.UTF_8), Instant.now()));
            return new SandboxResult(
                    0,
                    (mismatch ? "different" : "first").getBytes(StandardCharsets.UTF_8),
                    "tail".getBytes(StandardCharsets.UTF_8),
                    false,
                    false,
                    Duration.ofMillis(20));
        }

        @Override
        public SandboxSession open(
                SandboxCommand command,
                PermissionProfile permission,
                CancellationToken cancellation,
                SandboxRuntimeAccess access,
                SandboxNetworkAccess network) {
            throw new AssertionError("batch 不能改用 PTY");
        }
    }
}
