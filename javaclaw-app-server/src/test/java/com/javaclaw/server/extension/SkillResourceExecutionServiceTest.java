package com.javaclaw.server.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillResourceExecutionServiceTest {
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
    void 未配置发行镜像时不回退宿主Java并返回不可用状态() throws Exception {
        System.clearProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY);
        CanonicalJson json = new CanonicalJson();
        SkillResourceExecutionService service = SkillResourceExecutionService.unavailable(json);

        CanonicalPayload response = service.invoke(invocation(
                BuiltinExtensionIds.SKILL,
                json.encode(new SkillContracts.ResourceExecutionInvocation(
                        SkillContracts.ResourceExecutionOperation.STATUS, java.util.Optional.empty(), List.of()))));

        SkillContracts.ResourceExecutionAvailability availability =
                json.decode(response, SkillContracts.ResourceExecutionAvailability.class);
        assertFalse(availability.executable());
        assertTrue(availability.reason().contains("Native Sandbox"));
        assertTrue(SkillResourceRuntimeLayout.discover(temporaryDirectory).isEmpty());
    }

    @Test
    void 不完整发行镜像安全拒绝且不会搜索Path() throws Exception {
        Path image = Files.createDirectories(temporaryDirectory.resolve("runtime/bin"));
        System.setProperty(
                SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY,
                image.getParent().toString());

        assertThrows(IOException.class, () -> SkillResourceRuntimeLayout.discover(temporaryDirectory));
    }

    @Test
    void 非Skill扩展不能调用资源执行服务() {
        CanonicalJson json = new CanonicalJson();
        SkillResourceExecutionService service = SkillResourceExecutionService.unavailable(json);
        CanonicalPayload status = json.encode(new SkillContracts.ResourceExecutionInvocation(
                SkillContracts.ResourceExecutionOperation.STATUS, java.util.Optional.empty(), List.of()));

        assertThrows(SecurityException.class, () -> service.invoke(invocation(BuiltinExtensionIds.MEMORY, status)));
    }

    private static IsolatedServiceInvocation invocation(String caller, CanonicalPayload payload) {
        return new IsolatedServiceInvocation(
                new ExtensionId(caller),
                WorkspaceId.random(),
                permission(),
                SkillContracts.RESOURCE_EXECUTION_SERVICE,
                payload,
                new CancellationSource());
    }

    private static PermissionProfile permission() {
        return new PermissionProfile(
                "skill-test",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), false),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(512L * 1024 * 1024, 64L * 1024, 4, 128));
    }
}
