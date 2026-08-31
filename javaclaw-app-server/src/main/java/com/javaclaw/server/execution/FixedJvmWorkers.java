package com.javaclaw.server.execution;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import com.javaclaw.agent.knowledge.DocumentExtractionGateway;
import com.javaclaw.agent.knowledge.SkillResource;
import com.javaclaw.agent.knowledge.SkillScriptGateway;
import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolExecutionResult;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxSessionOptions;

/** 固定第一方 JVM Worker 的进程适配器；只委托 Supervisor，不能在本进程解析文档或执行用户代码。 */
public final class FixedJvmWorkers
        implements DocumentExtractionGateway, SkillScriptGateway, com.javaclaw.agent.tools.FileOperationGateway {
    private static final int MAGIC = 0x4a434457;
    private static final int MAX_OUTPUT = 36 * 1024 * 1024;
    private final SandboxExecutor sandbox;
    private final Path workspace;
    private final Set<Path> runtimeReads;
    private final String classpath;
    private final Set<Path> protectedRoots;

    /** 固定应用发行物 classpath 与工作目录；这些来自进程装配，不接受模型或 RPC 提供的命令。 */
    public FixedJvmWorkers(SandboxExecutor sandbox, Path workspace, Set<Path> protectedRoots)
            throws java.io.IOException {
        this.sandbox = java.util.Objects.requireNonNull(sandbox);
        this.workspace = workspace.toAbsolutePath().normalize();
        this.protectedRoots = Set.copyOf(protectedRoots);
        Files.createDirectories(this.workspace);
        var paths = new LinkedHashSet<Path>();
        String configured = System.getProperty("java.class.path");
        for (String entry : configured.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isBlank()) {
                Path path = Path.of(entry).toAbsolutePath().normalize();
                if (!Files.exists(path)) {
                    throw new IllegalStateException("worker classpath entry does not exist");
                }
                paths.add(path.toRealPath());
            }
        }
        classpath = paths.stream()
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        paths.add(Path.of(System.getProperty("java.home")).toRealPath());
        runtimeReads = Set.copyOf(paths);
    }

    @Override
    public String extract(byte[] content, String mediaType, String displayName) throws Exception {
        byte[] bytes = document("extract", content, mediaType, displayName, 0, 0);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            requireMagic(input);
            int count = input.readInt();
            if (count < 0 || count > MAX_OUTPUT) {
                throw new IllegalStateException("invalid document worker output length");
            }
            byte[] text = input.readNBytes(count);
            if (text.length != count || input.read() != -1) {
                throw new IllegalStateException("truncated or trailing document worker response");
            }
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(text))
                    .toString();
        }
    }

    @Override
    public List<PageImage> render(byte[] content, int firstPage, int pageCount) throws Exception {
        if (firstPage < 1 || pageCount < 1 || pageCount > 20) {
            throw new IllegalArgumentException("OCR can render at most twenty explicit pages");
        }
        byte[] bytes = document("render", content, "application/pdf", "document.pdf", firstPage, pageCount);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            requireMagic(input);
            int count = input.readInt();
            if (count < 1 || count > pageCount) {
                throw new IllegalStateException("invalid rendered page count");
            }
            var images = new ArrayList<PageImage>();
            for (int index = 0; index < count; index++) {
                int page = input.readInt();
                int length = input.readInt();
                if (page != firstPage + index || length < 1 || length > 5 * 1024 * 1024) {
                    throw new IllegalStateException("invalid page frame");
                }
                byte[] png = input.readNBytes(length);
                if (png.length != length) {
                    throw new IllegalStateException("truncated rendered page");
                }
                images.add(new PageImage(page, png));
            }
            if (input.read() != -1) {
                throw new IllegalStateException("trailing rendered page data");
            }
            return List.copyOf(images);
        }
    }

    @Override
    public ToolExecutionResult execute(SkillResource resource, ToolExecutionContext context, SandboxPolicy policy)
            throws Exception {
        if (!resource.executable()
                || !(resource.path().endsWith(".java") || resource.path().endsWith(".jsh"))) {
            throw new IllegalArgumentException("only explicitly declared Java/JShell resources are executable");
        }
        context.scope().check();
        SandboxPolicy bounded = infrastructurePolicy(policy, policy.timeout());
        var command = new SandboxCommand(
                context.call().id(),
                command("script"),
                context.config().workingDirectory(),
                Map.of(),
                bounded,
                resource.content());
        var result = sandbox.execute(command);
        context.scope().check();
        var item = new ThreadItem.CommandExecution(
                command.argv(),
                result.exitCode(),
                result.stdout(),
                result.stderr(),
                result.timedOut(),
                result.truncated());
        return new ToolExecutionResult(
                item, "Java script exit=" + result.exitCode() + "\n" + result.stdout() + "\n" + result.stderr());
    }

    @Override
    public ToolExecutionResult execute(FileRequest request, ToolExecutionContext context, SandboxPolicy policy)
            throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var input = json.createObjectNode();
        input.put("operation", request.operation());
        input.put("path", request.path());
        input.put("content", request.content());
        input.put("expectedSha256", request.expectedSha256());
        input.put("oldText", request.oldText());
        input.put("startLine", request.startLine());
        input.put("lineCount", request.lineCount());
        var blocked = input.putArray("protectedRoots");
        policy.protectedRoots().forEach(path -> blocked.add(path.toString()));
        context.scope().check();
        var command = new SandboxCommand(
                context.call().id(),
                command("files"),
                context.config().workingDirectory(),
                Map.of(),
                infrastructurePolicy(policy, policy.timeout()),
                input.toString());
        var response = sandbox.execute(command);
        context.scope().check();
        if (response.exitCode() != 0 || response.timedOut() || response.truncated()) {
            throw new IllegalStateException(
                    "file worker rejected the request, timed out, or exceeded output limits; reread before retrying writes");
        }
        var result = json.readTree(response.stdout());
        if (!result.isObject() || !"completed".equals(result.path("status").asText())) {
            throw new IllegalStateException("invalid file worker result");
        }
        ThreadItem item;
        if (Set.of("WRITE", "REPLACE").contains(request.operation())) {
            item = new ThreadItem.FileChange(
                    request.path(),
                    result.path("created").asBoolean()
                            ? ThreadItem.FileChange.ChangeKind.CREATE
                            : ThreadItem.FileChange.ChangeKind.UPDATE,
                    result.path("diff").asText());
        } else {
            var fields = new java.util.LinkedHashMap<String, String>();
            result.fields()
                    .forEachRemaining(entry -> fields.put(
                            entry.getKey(),
                            entry.getValue().isTextual()
                                    ? entry.getValue().asText()
                                    : entry.getValue().toString()));
            item = new ThreadItem.DynamicToolCall(context.call().name(), fields);
        }
        return new ToolExecutionResult(item, result.toString());
    }

    private byte[] document(String operation, byte[] content, String mediaType, String name, int first, int count)
            throws Exception {
        if (content == null || content.length < 1 || content.length > 256 * 1024 * 1024) {
            throw new IllegalArgumentException("document must be 1..256 MiB");
        }
        var header = new ByteArrayOutputStream();
        try (var frame = new DataOutputStream(header)) {
            frame.writeInt(MAGIC);
            frame.writeUTF(operation);
            frame.writeUTF(mediaType);
            frame.writeUTF(name);
            frame.writeInt(first);
            frame.writeInt(count);
            frame.writeInt(content.length);
        }
        SandboxPolicy policy =
                infrastructurePolicy(SandboxPolicy.readOnly(Set.of(workspace), protectedRoots), Duration.ofMinutes(2));
        policy = new SandboxPolicy(
                policy.mode(),
                policy.readableRoots(),
                policy.writableRoots(),
                policy.protectedRoots(),
                NetworkPolicy.disabled(),
                Set.of(),
                policy.timeout(),
                MAX_OUTPUT);
        var command = new SandboxCommand(
                "document-" + java.util.UUID.randomUUID(), command("document"), workspace, Map.of(), policy);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        try (var session = sandbox.openSession(command, SandboxSessionOptions.pipes())) {
            FutureTask<Void> upload = new FutureTask<>(() -> {
                session.write(header.toByteArray());
                for (int offset = 0; offset < content.length; offset += 65_536) {
                    session.write(
                            java.util.Arrays.copyOfRange(content, offset, Math.min(content.length, offset + 65_536)));
                }
                session.closeInput();
                return null;
            });
            Thread writer = Thread.ofVirtual().name("javaclaw-document-upload").start(upload);
            var output = new ByteArrayOutputStream();
            int diagnostics = 0;
            try {
                while (System.nanoTime() < deadline) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("document extraction interrupted");
                    }
                    var frame = session.read(Duration.ofMillis(100));
                    if (frame == null) {
                        if (!session.isAlive()) {
                            throw new IllegalStateException("document worker exited without a result frame");
                        }
                        continue;
                    }
                    switch (frame.kind()) {
                        case READY -> {}
                        case STDOUT -> {
                            byte[] bytes = frame.data();
                            if (output.size() + bytes.length > MAX_OUTPUT) {
                                throw new IllegalStateException("document worker exceeded output limit");
                            }
                            output.write(bytes);
                        }
                        case STDERR -> {
                            diagnostics += frame.data().length;
                            if (diagnostics > 65_536) {
                                throw new IllegalStateException("document worker exceeded diagnostic limit");
                            }
                        }
                        case ERROR -> throw new IllegalStateException("document worker sandbox rejected execution");
                        case EXIT -> {
                            upload.get(1, TimeUnit.SECONDS);
                            if (frame.exitCode() != 0 || frame.truncated()) {
                                throw new IllegalStateException("document worker failed or output was truncated");
                            }
                            return output.toByteArray();
                        }
                    }
                }
                throw new IllegalStateException("document worker timeout");
            } finally {
                upload.cancel(true);
                writer.interrupt();
            }
        }
    }

    private SandboxPolicy infrastructurePolicy(SandboxPolicy policy, Duration duration) {
        var reads = new LinkedHashSet<>(policy.readableRoots());
        // 只添加装配时固定的产品/JDK 代码位置；不会加入数据根、用户主目录或客户端提供路径。
        reads.addAll(runtimeReads);
        return new SandboxPolicy(
                policy.mode(),
                reads,
                policy.writableRoots(),
                policy.protectedRoots(),
                NetworkPolicy.disabled(),
                Set.of(),
                duration,
                policy.outputLimitBytes());
    }

    private List<String> command(String operation) {
        String main =
                switch (operation) {
                    case "document" -> "com.javaclaw.agent.knowledge.DocumentWorkerMain";
                    case "script" -> "com.javaclaw.agent.tools.JShellWorkerMain";
                    case "files" -> "com.javaclaw.agent.tool.FileWorkerMain";
                    default -> throw new IllegalArgumentException("unknown fixed worker");
                };
        return List.of(
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                        .toString(),
                "-Xmx512m",
                "-Djava.awt.headless=true",
                "--add-modules=jdk.jshell",
                "-cp",
                classpath,
                main);
    }

    private static void requireMagic(DataInputStream input) throws java.io.IOException {
        if (input.readInt() != MAGIC) {
            throw new IllegalStateException("invalid document worker response");
        }
    }
}
