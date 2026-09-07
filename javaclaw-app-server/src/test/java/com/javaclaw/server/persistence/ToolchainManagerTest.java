package com.javaclaw.server.persistence;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstallRequest;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstallationState;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.coding.ToolchainManager;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolchainManagerTest {
    @TempDir
    Path temporary;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.systemUTC();
    private final AtomicInteger downloads = new AtomicInteger();
    private H2Database database;
    private ExtensionJobService jobs;
    private Workspace workspace;
    private byte[] archive;
    private ToolchainArtifact node;
    private ToolchainArtifact npm;

    @BeforeEach
    void 创建私有数据根和完全离线制品() throws Exception {
        database = new H2Database(temporary.resolve("data-v6"));
        database.initialize();
        jobs = new ExtensionJobService(database, json, clock);
        var payload = new CoreRpcContracts.WorkspaceCreatePayload("工具链测试", temporary.resolve("workspace"));
        var identity =
                CommandIdentity.from("workspace/create", new WriteCommand("workspace", 0, json.encode(payload)), json);
        workspace =
                new CoreCommandService(database, json, clock).createWorkspace(identity, payload.name(), payload.root());
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            entry(zip, "node/bin/node", "fake-node");
            entry(zip, "node/lib/npm-cli.js", "fake-npm");
        }
        archive = bytes.toByteArray();
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
        node = artifact(ToolchainKind.NODE, digest, "node", "node/bin/node");
        npm = artifact(ToolchainKind.NPM, digest, "npm", "node/lib/npm-cli.js");
    }

    @Test
    void 安装受理与Job原子保存且不同Kind共享只读物理归档() throws Exception {
        try (var manager = manager()) {
            var accepted = manager.install(request(node, "node"), new CancellationSource());
            assertEquals(accepted, manager.install(request(node, "node"), new CancellationSource()));
            assertEquals(accepted, manager.install(request(node, "node-again"), new CancellationSource()));
            var other = manager.install(request(npm, "npm"), new CancellationSource());
            assertNotEquals(accepted.jobId(), other.jobId());
            var supervisor = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
            manager.registerJobs(supervisor);
            assertTrue(supervisor.runOnce());
            assertTrue(supervisor.runOnce());
            assertFalse(supervisor.runOnce());
            assertEquals(1, downloads.get());
            assertEquals(
                    ExecutionState.COMPLETED,
                    jobs.find(accepted.jobId()).orElseThrow().state());
            assertTrue(manager.installed(workspace.id()).toolchains().stream()
                    .allMatch(item -> item.state() == InstallationState.READY));
            try (var lease = manager.acquire(workspace.id(), List.of(node.reference(), npm.reference()))) {
                assertEquals(
                        lease.installations().get(ToolchainKind.NODE).root(),
                        lease.installations().get(ToolchainKind.NPM).root());
                assertEquals(
                        "fake-node",
                        Files.readString(lease.installations()
                                .get(ToolchainKind.NODE)
                                .root()
                                .resolve("node/bin/node")));
            }
        }
    }

    @Test
    void 发布完成但Job尚未提交时恢复原意图且不重新下载() throws Exception {
        try (var manager = manager()) {
            var accepted = manager.install(request(node, "crash"), new CancellationSource());
            var executor = new AtomicReference<ExtensionJobExecutor>();
            manager.registerJobs((ignored, registration) -> executor.set(registration.executor()));
            var claimed = jobs.claimNext().orElseThrow();
            var planned = executor.get().plan(claimed.job()).orElseThrow();
            var recorded = jobs.recordIntent(claimed, planned);
            executor.get()
                    .execute(
                            new ExtensionJobExecution(
                                    recorded.job(), recorded.unit().orElseThrow()),
                            new CancellationSource());
            assertEquals(1, downloads.get());
            assertEquals(
                    ExecutionState.RUNNING,
                    jobs.find(accepted.jobId()).orElseThrow().state());
            assertEquals(1, jobs.recoverOutbox());
            var supervisor = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
            manager.registerJobs(supervisor);
            assertTrue(supervisor.runOnce());
            assertEquals(1, downloads.get());
            assertEquals(
                    ExecutionState.COMPLETED,
                    jobs.find(accepted.jobId()).orElseThrow().state());
        }
    }

    @Test
    void 校验失败记录失败且新命令可修复但旧幂等结果不改写() throws Exception {
        try (var manager = manager()) {
            var accepted = manager.install(request(node, "first"), new CancellationSource());
            var supervisor = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
            manager.registerJobs(supervisor);
            supervisor.runOnce();
            Path entry;
            try (var lease = manager.acquire(workspace.id(), List.of(node.reference()))) {
                entry = lease.installations().get(ToolchainKind.NODE).root().resolve("node/bin/node");
            }
            assertTrue(entry.toFile().setWritable(true));
            Files.writeString(entry, "corrupted");
            assertThrows(java.io.IOException.class, () -> manager.acquire(workspace.id(), List.of(node.reference())));
            var retried = manager.install(request(node, "repair"), new CancellationSource());
            assertNotEquals(accepted.jobId(), retried.jobId());
            assertEquals(accepted, manager.install(request(node, "first"), new CancellationSource()));
            assertTrue(supervisor.runOnce());
            assertEquals(2, downloads.get());
            assertEquals(
                    ExecutionState.COMPLETED,
                    jobs.find(retried.jobId()).orElseThrow().state());
        }
    }

    @Test
    void 下载摘要不匹配不能产生就绪安装或残留归档() throws Exception {
        archive[archive.length - 1] ^= 1;
        try (var manager = manager()) {
            var accepted = manager.install(request(node, "bad-download"), new CancellationSource());
            var supervisor = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
            manager.registerJobs(supervisor);
            assertTrue(supervisor.runOnce());
            assertEquals(
                    ExecutionState.FAILED,
                    jobs.find(accepted.jobId()).orElseThrow().state());
            assertEquals(
                    InstallationState.FAILED,
                    manager.installed(workspace.id()).toolchains().getFirst().state());
            assertThrows(java.io.IOException.class, () -> manager.acquire(workspace.id(), List.of(node.reference())));
            try (var staging = Files.list(database.dataRoot().resolve("coding/toolchains/staging"))) {
                assertEquals(0, staging.count());
            }
        }
    }

    @Test
    void 后台下载不阻塞其他命令释放租约且关闭可取消下载() throws Exception {
        byte[] secondArchive = java.util.Arrays.copyOf(archive, archive.length + 1);
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secondArchive));
        var python = new ToolchainArtifact(
                new ToolchainRef(ToolchainKind.PYTHON, "1.0", digest),
                node.platform(),
                node.architecture(),
                node.downloadUri(),
                "zip",
                Map.of("python", "node/bin/node"),
                secondArchive.length,
                "MIT");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var manager = new ToolchainManager(new ToolchainManager.Dependencies(
                database,
                jobs,
                new CodingToolchainCatalog(List.of(node, python), node.platform(), node.architecture()),
                json,
                clock,
                (artifact, target, cancellation) -> {
                    if (artifact.reference().kind() == ToolchainKind.PYTHON) {
                        entered.countDown();
                        while (!release.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            cancellation.throwIfCancelled();
                        }
                        target.write(secondArchive);
                    } else {
                        target.write(archive);
                    }
                }));
        var supervisor = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
        manager.registerJobs(supervisor);
        manager.install(request(node, "first-runtime"), new CancellationSource());
        supervisor.runOnce();
        var lease = manager.acquire(workspace.id(), List.of(node.reference()));
        var accepted = manager.install(request(python, "second-runtime"), new CancellationSource());
        Thread worker = Thread.ofVirtual().start(supervisor::runOnce);
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), lease::close);
            manager.close();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(
                    ExecutionState.FAILED,
                    jobs.find(accepted.jobId()).orElseThrow().state());
        } finally {
            release.countDown();
            manager.close();
            worker.join(5000);
            lease.close();
        }
    }

    private ToolchainManager manager() throws Exception {
        return new ToolchainManager(new ToolchainManager.Dependencies(
                database,
                jobs,
                new CodingToolchainCatalog(List.of(node, npm), node.platform(), node.architecture()),
                json,
                clock,
                (artifact, target, cancellation) -> {
                    downloads.incrementAndGet();
                    target.write(archive);
                }));
    }

    private ExtensionRequest request(ToolchainArtifact artifact, String key) {
        return new ExtensionRequest(
                workspace.id(),
                Optional.empty(),
                Optional.empty(),
                "toolchain/install",
                json.encode(new InstallRequest(artifact.reference())),
                Optional.of(key),
                0,
                Optional.empty());
    }

    private ToolchainArtifact artifact(ToolchainKind kind, String digest, String name, String path) {
        return new ToolchainArtifact(
                new ToolchainRef(kind, "1.0", digest),
                CodingToolchainCatalog.platform(),
                CodingToolchainCatalog.architecture(),
                URI.create("https://example.test/artifact.zip"),
                "zip",
                Map.of(name, path),
                archive.length,
                "MIT");
    }

    private static void entry(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
