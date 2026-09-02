package com.javaclaw.server.extension;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillResourceExecutionValidationTest {
    @TempDir
    Path temporaryDirectory;

    private final String originalImageRoot = System.getProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY);

    @AfterEach
    void 恢复发行镜像配置() {
        if (originalImageRoot == null) {
            System.clearProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY);
        } else {
            System.setProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY, originalImageRoot);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 生产服务对缺失或损坏镜像均失败关闭() throws Exception {
        CanonicalJson json = new CanonicalJson();
        H2Database database = database("production-data-v5");
        AttachmentService attachments = new AttachmentService(database, json, Clock.systemUTC());

        System.clearProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY);
        SkillResourceExecutionService missing =
                SkillResourceExecutionService.production(database.dataRoot(), attachments, json);
        assertFalse(missing.isAvailable());
        assertTrue(status(missing, json).reason().contains("未配置"));

        System.setProperty(
                SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY,
                temporaryDirectory.resolve("missing-runtime").toString());
        SkillResourceExecutionService broken =
                SkillResourceExecutionService.production(database.dataRoot(), attachments, json);
        assertFalse(broken.isAvailable());
        assertTrue(status(broken, json).reason().contains("不完整"));
    }

    @Test
    void 不可用服务拒绝执行与伪造服务标识() throws Exception {
        CanonicalJson json = new CanonicalJson();
        H2Database database = database("unavailable-data-v5");
        AttachmentService attachments = new AttachmentService(database, json, Clock.systemUTC());
        SkillResourceExecutionService service = SkillResourceExecutionService.unavailable(attachments, json);
        WorkspaceId workspaceId = WorkspaceId.random();
        SkillContracts.Resource resource =
                new SkillContracts.Resource("main", SkillContracts.JAVA_SOURCE_MEDIA_TYPE, "a".repeat(64), true);
        CanonicalPayload execute = json.encode(new SkillContracts.ResourceExecutionInvocation(
                SkillContracts.ResourceExecutionOperation.EXECUTE, Optional.of(resource), List.of()));

        assertThrows(
                IllegalStateException.class,
                () -> service.invoke(invocation(workspaceId, SkillContracts.RESOURCE_EXECUTION_SERVICE, execute)));
        assertThrows(
                SecurityException.class,
                () -> service.invoke(invocation(workspaceId, "skill.resource.other", execute)));
        assertThrows(NullPointerException.class, () -> service.invoke(null));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 执行前拒绝取消损坏编码与不匹配元数据() throws Exception {
        CanonicalJson json = new CanonicalJson();
        H2Database database = database("validation-data-v5");
        AttachmentService attachments = new AttachmentService(database, json, Clock.systemUTC());
        SkillResourceRuntimeLayout layout = layout(database.dataRoot(), "validation-image");
        SkillResourceExecutionService service = available(attachments, json, layout);
        WorkspaceId workspaceId = createWorkspace(database, json, "validation-workspace");

        byte[] mismatched = "class Main {}".getBytes(StandardCharsets.UTF_8);
        AttachmentMetadata wrongMedia = store(attachments, workspaceId, "text/plain", mismatched);
        assertThrows(
                SecurityException.class,
                () -> execute(
                        service,
                        json,
                        workspaceId,
                        resource(wrongMedia, SkillContracts.JAVA_SOURCE_MEDIA_TYPE),
                        List.of(),
                        new CancellationSource()));

        byte[] malformed = {(byte) 0xC3, (byte) 0x28};
        AttachmentMetadata malformedMetadata =
                store(attachments, workspaceId, SkillContracts.JAVA_SOURCE_MEDIA_TYPE, malformed);
        assertThrows(
                java.nio.charset.CharacterCodingException.class,
                () -> execute(
                        service, json, workspaceId, resource(malformedMetadata), List.of(), new CancellationSource()));

        AttachmentMetadata nulMetadata =
                store(attachments, workspaceId, SkillContracts.JAVA_SOURCE_MEDIA_TYPE, new byte[] {
                    'c', 'l', 'a', 's', 's', 0, 'M'
                });
        assertThrows(
                SecurityException.class,
                () -> execute(service, json, workspaceId, resource(nulMetadata), List.of(), new CancellationSource()));

        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("test-cancelled");
        assertThrows(
                TurnCancelledException.class,
                () -> execute(service, json, workspaceId, resource(malformedMetadata), List.of(), cancelled));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void JShell参数校验失败后仍清理任务目录() throws Exception {
        CanonicalJson json = new CanonicalJson();
        H2Database database = database("cleanup-data-v5");
        AttachmentService attachments = new AttachmentService(database, json, Clock.systemUTC());
        SkillResourceRuntimeLayout layout = layout(database.dataRoot(), "cleanup-image");
        SkillResourceExecutionService service = available(attachments, json, layout);
        WorkspaceId workspaceId = createWorkspace(database, json, "cleanup-workspace");
        AttachmentMetadata metadata = store(
                attachments,
                workspaceId,
                SkillContracts.JSHELL_MEDIA_TYPE,
                "System.out.println(1);".getBytes(StandardCharsets.UTF_8));

        assertThrows(
                IllegalArgumentException.class,
                () -> execute(
                        service,
                        json,
                        workspaceId,
                        resource(metadata, SkillContracts.JSHELL_MEDIA_TYPE),
                        List.of("forbidden"),
                        new CancellationSource()));
        try (var tasks = Files.list(database.dataRoot().resolve("skill-worker/tmp"))) {
            assertTrue(tasks.findAny().isEmpty());
        }
    }

    private H2Database database(String name) {
        H2Database database = new H2Database(temporaryDirectory.resolve(name).resolve("data-v5"));
        database.initialize();
        return database;
    }

    private WorkspaceId createWorkspace(H2Database database, CanonicalJson json, String name) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve(name));
        return new CoreCommandService(database, json, Clock.systemUTC())
                .createWorkspace(
                        new CommandIdentity(
                                "workspace/create",
                                UUID.randomUUID().toString(),
                                0,
                                json.encode(java.util.Map.of("name", name)).sha256()),
                        name,
                        root)
                .id();
    }

    private SkillResourceRuntimeLayout layout(Path dataRoot, String name) throws Exception {
        Path image = Files.createDirectories(temporaryDirectory.resolve(name));
        Path bin = Files.createDirectories(image.resolve("bin"));
        executable(bin.resolve(executableName("java")));
        executable(bin.resolve(executableName("jshell")));
        Files.writeString(
                image.resolve("worker-image-v1.capability"), "worker-image-v1:skill", StandardCharsets.US_ASCII);
        System.setProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY, image.toString());
        return SkillResourceRuntimeLayout.discover(dataRoot).orElseThrow();
    }

    private static void executable(Path path) throws Exception {
        Files.writeString(path, "executable", StandardCharsets.US_ASCII);
        assertTrue(path.toFile().setExecutable(true, true) || Files.isExecutable(path));
    }

    private static SkillResourceExecutionService available(
            AttachmentService attachments, CanonicalJson json, SkillResourceRuntimeLayout layout) throws Exception {
        Constructor<SkillResourceExecutionService> constructor =
                SkillResourceExecutionService.class.getDeclaredConstructor(
                        Optional.class,
                        CanonicalJson.class,
                        Optional.class,
                        SkillContracts.ResourceExecutionAvailability.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                Optional.of(attachments),
                json,
                Optional.of(layout),
                new SkillContracts.ResourceExecutionAvailability(true, ""));
    }

    private static AttachmentMetadata store(
            AttachmentService attachments, WorkspaceId workspaceId, String mediaType, byte[] content) {
        return attachments.store(
                AttachmentScope.workspace(workspaceId),
                new CommandIdentity("attachment/store", UUID.randomUUID().toString(), 0, "0".repeat(64)),
                mediaType,
                content);
    }

    private static SkillContracts.Resource resource(AttachmentMetadata metadata) {
        return resource(metadata, metadata.mediaType());
    }

    private static SkillContracts.Resource resource(AttachmentMetadata metadata, String mediaType) {
        return new SkillContracts.Resource("resource", mediaType, metadata.digest(), true);
    }

    private static void execute(
            SkillResourceExecutionService service,
            CanonicalJson json,
            WorkspaceId workspaceId,
            SkillContracts.Resource resource,
            List<String> arguments,
            CancellationSource cancellation)
            throws Exception {
        CanonicalPayload payload = json.encode(new SkillContracts.ResourceExecutionInvocation(
                SkillContracts.ResourceExecutionOperation.EXECUTE, Optional.of(resource), arguments));
        service.invoke(invocation(workspaceId, SkillContracts.RESOURCE_EXECUTION_SERVICE, payload, cancellation));
    }

    private static SkillContracts.ResourceExecutionAvailability status(
            SkillResourceExecutionService service, CanonicalJson json) throws Exception {
        CanonicalPayload payload = json.encode(new SkillContracts.ResourceExecutionInvocation(
                SkillContracts.ResourceExecutionOperation.STATUS, Optional.empty(), List.of()));
        return json.decode(
                service.invoke(invocation(WorkspaceId.random(), SkillContracts.RESOURCE_EXECUTION_SERVICE, payload)),
                SkillContracts.ResourceExecutionAvailability.class);
    }

    private static IsolatedServiceInvocation invocation(
            WorkspaceId workspaceId, String serviceId, CanonicalPayload payload) {
        return invocation(workspaceId, serviceId, payload, new CancellationSource());
    }

    private static IsolatedServiceInvocation invocation(
            WorkspaceId workspaceId, String serviceId, CanonicalPayload payload, CancellationSource cancellation) {
        return new IsolatedServiceInvocation(
                new ExtensionId(BuiltinExtensionIds.SKILL),
                workspaceId,
                permission(),
                serviceId,
                payload,
                cancellation);
    }

    private static PermissionProfile permission() {
        return new PermissionProfile(
                "skill-validation",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), false),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(512L * 1024 * 1024, 64L * 1024, 4, 128));
    }

    private static String executableName(String basename) {
        return System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("windows")
                ? basename + ".exe"
                : basename;
    }
}
