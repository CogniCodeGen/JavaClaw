package com.javaclaw.launcher;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.knowledge.SkillResource;
import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.tool.LauncherProcessSandboxExecutor;
import com.javaclaw.agent.tools.FileOperationGateway;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.protocol.RpcMethods;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.server.execution.FixedJvmWorkers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedWorkerTest {
    @TempDir
    Path temporary;

    @Test
    void nativeDocumentAndJavaWorkersParseContentWithoutHostFilesystemOrNetworkAccess() throws Exception {
        if (!Boolean.getBoolean("javaclaw.require.native.sandbox")) {
            return;
        }
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path forbidden = temporary.resolve("private.txt");
        Files.writeString(forbidden, "NEVER_EXPOSE_WORKER_SECRET");
        var workers = workers(workspace);
        assertEquals(
                "隔离文档",
                workers.extract("隔离文档".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain", "note.txt"));
        byte[] document;
        try (var docx = new XWPFDocument();
                var output = new ByteArrayOutputStream()) {
            docx.createParagraph().createRun().setText("Worker DOCX evidence");
            docx.write(output);
            document = output.toByteArray();
        }
        assertTrue(workers.extract(
                        document,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "evidence.docx")
                .contains("Worker DOCX evidence"));
        var readOnly = policy(workspace, false);
        var context = context(workspace, readOnly);
        var success = workers.execute(
                new SkillResource("test.jsh", "text/x-java", "System.out.println(6 * 7);", true), context, readOnly);
        var command = (com.javaclaw.core.api.ThreadItem.CommandExecution) success.item();
        assertEquals(0, command.exitCode(), command.stderr());
        assertTrue(command.stdout().contains("42"));
        String literal = new ObjectMapper().writeValueAsString(forbidden.toString());
        var denied = workers.execute(
                new SkillResource(
                        "escape.jsh",
                        "text/x-java",
                        "System.out.println(java.nio.file.Files.readString(java.nio.file.Path.of(" + literal + ")));",
                        true),
                context,
                readOnly);
        var rejected = (com.javaclaw.core.api.ThreadItem.CommandExecution) denied.item();
        assertNotEquals(0, rejected.exitCode());
        assertFalse(rejected.stdout().contains("NEVER_EXPOSE_WORKER_SECRET"));
        var network = workers.execute(
                new SkillResource("network.jsh", "text/x-java", "new java.net.Socket(\"127.0.0.1\", 9);", true),
                context,
                readOnly);
        assertNotEquals(0, ((com.javaclaw.core.api.ThreadItem.CommandExecution) network.item()).exitCode());
    }

    @Test
    void nativeFileWorkerEnforcesReadonlyAndExpectedHashBeforeAtomicChanges() throws Exception {
        if (!Boolean.getBoolean("javaclaw.require.native.sandbox")) {
            return;
        }
        Path workspace = Files.createDirectories(temporary.resolve("files"));
        var workers = workers(workspace);
        var readOnly = policy(workspace, false);
        var write = policy(workspace, true);
        var create = new FileOperationGateway.FileRequest("WRITE", "note.txt", "old text\n", "MISSING", "", 1, 100);
        assertThrows(
                IllegalStateException.class, () -> workers.execute(create, context(workspace, readOnly), readOnly));
        workers.execute(create, context(workspace, write), write);
        var read = workers.execute(
                new FileOperationGateway.FileRequest("READ", "note.txt", "", "", "", 1, 100),
                context(workspace, readOnly),
                readOnly);
        String hash =
                new ObjectMapper().readTree(read.modelContent()).path("sha256").asText();
        assertEquals(64, hash.length());
        workers.execute(
                new FileOperationGateway.FileRequest("REPLACE", "note.txt", "new", hash, "old", 1, 100),
                context(workspace, write),
                write);
        assertEquals("new text\n", Files.readString(workspace.resolve("note.txt")));
        assertThrows(
                IllegalStateException.class,
                () -> workers.execute(
                        new FileOperationGateway.FileRequest("WRITE", "note.txt", "stale", hash, "", 1, 100),
                        context(workspace, write),
                        write));
        assertEquals("new text\n", Files.readString(workspace.resolve("note.txt")));
    }

    private static FixedJvmWorkers workers(Path workspace) throws Exception {
        List<Path> modules = new ArrayList<>();
        modules.add(location(ThreadId.class));
        modules.add(location(RpcMethods.class));
        modules.add(location(Class.forName(
                "com.javaclaw.nativehost.sandbox.SandboxLauncherMain",
                false,
                IsolatedWorkerTest.class.getClassLoader())));
        modules.add(location(ObjectMapper.class));
        modules.add(location(JsonFactory.class));
        modules.add(location(JsonProperty.class));
        modules.add(location(JavaTimeModule.class));
        modules.add(location(com.fasterxml.jackson.datatype.jdk8.Jdk8Module.class));
        var sandbox = new LauncherProcessSandboxExecutor(LauncherProcessSandboxExecutor.modularJavaCommand(modules));
        return new FixedJvmWorkers(sandbox, workspace, Set.of(workspace.resolve(".javaclaw")));
    }

    private static Path location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static SandboxPolicy policy(Path workspace, boolean writable) {
        return new SandboxPolicy(
                writable ? SandboxMode.WORKSPACE_WRITE : SandboxMode.READ_ONLY,
                Set.of(workspace),
                writable ? Set.of(workspace) : Set.of(),
                Set.of(workspace.resolve(".git"), workspace.resolve(".javaclaw")),
                NetworkPolicy.disabled(),
                Set.of(),
                Duration.ofSeconds(30),
                2 * 1024 * 1024);
    }

    private static ToolExecutionContext context(Path workspace, SandboxPolicy policy) {
        var now = Instant.now();
        var thread = new AgentThread(
                new ThreadId("worker-thread"),
                "worker-workspace",
                null,
                null,
                "worker",
                workspace,
                ThreadStatus.ACTIVE,
                0,
                0,
                1,
                now,
                now);
        var config =
                new TurnConfig("fake", "fake", "medium", workspace, policy, ApprovalPolicy.NEVER, Set.of(), Map.of());
        var turn = new AgentTurn(
                new TurnId("worker-turn"),
                thread.id(),
                new AttemptId("worker-attempt"),
                TurnStatus.IN_PROGRESS,
                List.of(),
                config,
                null,
                now,
                null);
        return new ToolExecutionContext(thread, turn, new ModelToolCall("worker-call", "worker", "{}"), config);
    }
}
