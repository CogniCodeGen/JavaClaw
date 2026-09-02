package com.javaclaw.server.extension;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.nativehost.sandbox.SandboxErrorMode;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerLauncher;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;

/** 读取内容寻址 Attachment，并在无原始网络的 Native Sandbox 中执行 Java/JShell。 */
final class SkillResourceExecutionService {
    private static final long MAXIMUM_SOURCE_BYTES = 256L * 1024;
    private static final Duration OUTPUT_JOIN_TIMEOUT = Duration.ofSeconds(2);

    private final Optional<AttachmentService> attachments;
    private final CanonicalJson json;
    private final Optional<SkillResourceRuntimeLayout> runtime;
    private final SkillContracts.ResourceExecutionAvailability availability;
    private final SandboxedWorkerLauncher launcher = new SandboxedWorkerLauncher();

    private SkillResourceExecutionService(
            Optional<AttachmentService> attachments,
            CanonicalJson json,
            Optional<SkillResourceRuntimeLayout> runtime,
            SkillContracts.ResourceExecutionAvailability availability) {
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.json = Objects.requireNonNull(json, "json");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.availability = Objects.requireNonNull(availability, "availability");
    }

    static SkillResourceExecutionService production(Path dataRoot, AttachmentService attachments, CanonicalJson json) {
        try {
            Optional<SkillResourceRuntimeLayout> discovered =
                    SkillResourceRuntimeLayout.discover(Objects.requireNonNull(dataRoot, "dataRoot"));
            if (discovered.isEmpty()) {
                return unavailable(attachments, json, "发行镜像未配置 Skill Java/JShell runtime");
            }
            SkillResourceRuntimeLayout layout = discovered.orElseThrow();
            String failure = probe(layout);
            if (!failure.isEmpty()) {
                return unavailable(attachments, json, failure);
            }
            return new SkillResourceExecutionService(
                    Optional.of(attachments),
                    json,
                    Optional.of(layout),
                    new SkillContracts.ResourceExecutionAvailability(true, ""));
        } catch (IOException | RuntimeException failure) {
            return unavailable(attachments, json, "Skill Java/JShell 发行镜像不完整");
        }
    }

    static SkillResourceExecutionService unavailable(AttachmentService attachments, CanonicalJson json) {
        return unavailable(attachments, json, "Skill Java/JShell Native Sandbox 不可用");
    }

    static SkillResourceExecutionService unavailable(CanonicalJson json) {
        return new SkillResourceExecutionService(
                Optional.empty(),
                json,
                Optional.empty(),
                new SkillContracts.ResourceExecutionAvailability(false, "Skill Java/JShell Native Sandbox 不可用"));
    }

    boolean isAvailable() {
        return availability.executable();
    }

    CanonicalPayload invoke(IsolatedServiceInvocation invocation) throws Exception {
        IsolatedServiceInvocation checked = Objects.requireNonNull(invocation, "invocation");
        requireAuthorized(checked);
        SkillContracts.ResourceExecutionInvocation request =
                json.decode(checked.request(), SkillContracts.ResourceExecutionInvocation.class);
        if (request.operation() == SkillContracts.ResourceExecutionOperation.STATUS) {
            return json.encode(availability);
        }
        if (!availability.executable()) {
            throw new IllegalStateException(availability.reason());
        }
        SkillContracts.Resource resource = request.resource().orElseThrow();
        return json.encode(execute(checked, resource, request.arguments()));
    }

    private SkillContracts.ResourceExecutionResult execute(
            IsolatedServiceInvocation invocation, SkillContracts.Resource resource, java.util.List<String> arguments)
            throws Exception {
        invocation.cancellation().throwIfCancelled();
        AttachmentContent attachment =
                attachments.orElseThrow().read(AttachmentScope.workspace(invocation.workspaceId()), resource.digest());
        requireAttachment(resource, attachment);
        byte[] content = attachment.content();
        Path task = null;
        Throwable primary = null;
        try {
            requireUtf8(content);
            SkillResourceRuntimeLayout layout = runtime.orElseThrow();
            task = layout.createTaskDirectory();
            Path source = task.resolve(layout.sourceFileName(resource));
            Files.write(source, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return run(invocation, layout, resource, source, arguments);
        } catch (Exception failure) {
            primary = failure;
            throw failure;
        } catch (Error failure) {
            primary = failure;
            throw failure;
        } finally {
            Arrays.fill(content, (byte) 0);
            cleanup(task, primary);
        }
    }

    private SkillContracts.ResourceExecutionResult run(
            IsolatedServiceInvocation invocation,
            SkillResourceRuntimeLayout layout,
            SkillContracts.Resource resource,
            Path source,
            java.util.List<String> arguments)
            throws Exception {
        long started = System.nanoTime();
        Process process =
                launcher.start(layout.execution(resource, source, arguments), SandboxErrorMode.MERGE_WITH_OUTPUT);
        FutureTask<BoundedProcessOutput.Capture> output = new FutureTask<>(() ->
                BoundedProcessOutput.read(process.getInputStream(), SkillResourceRuntimeLayout.MAXIMUM_OUTPUT_BYTES));
        Thread.ofVirtual().name("javaclaw-skill-output").start(output);
        try {
            process.getOutputStream().close();
            await(process, invocation);
            BoundedProcessOutput.Capture captured = output.get(OUTPUT_JOIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            return new SkillContracts.ResourceExecutionResult(
                    process.exitValue(), captured.text(), captured.truncated(), elapsed);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            if (!output.isDone()) {
                output.cancel(true);
            }
        }
    }

    private static void await(Process process, IsolatedServiceInvocation invocation) throws InterruptedException {
        long deadline = System.nanoTime() + SkillResourceRuntimeLayout.EXECUTION_TIMEOUT.toNanos();
        while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
            invocation.cancellation().throwIfCancelled();
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Skill resource execution timed out");
            }
        }
        invocation.cancellation().throwIfCancelled();
    }

    private static void requireAttachment(SkillContracts.Resource resource, AttachmentContent attachment) {
        var metadata = attachment.metadata();
        if (!metadata.digest().equals(resource.digest())
                || !metadata.mediaType().equals(resource.mediaType())
                || metadata.sizeBytes() < 1
                || metadata.sizeBytes() > MAXIMUM_SOURCE_BYTES) {
            throw new SecurityException("Skill resource Attachment metadata does not match the published digest");
        }
    }

    private static void requireUtf8(byte[] content) throws Exception {
        StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content));
        if (indexOfNul(content)) {
            throw new SecurityException("Skill source contains a NUL byte");
        }
    }

    private static boolean indexOfNul(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static void requireAuthorized(IsolatedServiceInvocation invocation) {
        if (!BuiltinExtensionIds.SKILL.equals(invocation.caller().value())
                || !SkillContracts.RESOURCE_EXECUTION_SERVICE.equals(invocation.serviceId())) {
            throw new SecurityException("caller is not authorized for Skill resource execution");
        }
    }

    private static String probe(SkillResourceRuntimeLayout layout) {
        Process process = null;
        try {
            process = new SandboxedWorkerLauncher().start(layout.probe());
            process.getOutputStream().close();
            if (!process.waitFor(6, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return "Skill Java/JShell Native Sandbox 自检未通过";
            }
            return "";
        } catch (IOException | RuntimeException failure) {
            return "Skill Java/JShell Native Sandbox 不可用";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "Skill Java/JShell Native Sandbox 自检被中断";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static SkillResourceExecutionService unavailable(
            AttachmentService attachments, CanonicalJson json, String reason) {
        return new SkillResourceExecutionService(
                Optional.of(attachments),
                json,
                Optional.empty(),
                new SkillContracts.ResourceExecutionAvailability(false, reason));
    }

    private static void cleanup(Path task, Throwable primary) throws Exception {
        if (task == null) {
            return;
        }
        try (var paths = Files.walk(task)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException cleanupFailure) {
            if (primary != null) {
                primary.addSuppressed(cleanupFailure);
                return;
            }
            throw new IllegalStateException("Skill Worker temporary files could not be removed", cleanupFailure);
        }
    }
}
