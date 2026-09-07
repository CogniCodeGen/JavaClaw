package com.javaclaw.server.toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在当前 macOS 宿主实际执行断网文件 Worker；不把跨平台编译当作平台验收。 */
@EnabledOnOs(OS.MAC)
class ProjectToolchainNativeTest {
    @TempDir
    Path temporary;

    @Test
    void 真实原生受限读取保留缺失声明证据并选择兼容版本() throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve(".java-version"), "21");
        Files.writeString(root.resolve(".tool-versions"), "java 21\nnodejs 22.18.0\npython 3.12.11\n");
        var permission = new PermissionProfile(
                "project-declarations",
                1,
                new FilePermission(List.of(root), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, 4L * 1024 * 1024, 8, 128));
        var catalog = CodingToolchainCatalog.bundled();
        var selection = new ProjectToolchainSelector(catalog)
                .select(root, permission, new Environment(0, catalog.defaultEnvironment()));
        assertTrue(selection.inspected());
        selection.requireCompatible(List.of(ToolchainKind.JDK, ToolchainKind.NODE, ToolchainKind.PYTHON));
        assertEquals(
                Set.of(".java-version", ".tool-versions"),
                selection.declarations().keySet());
        assertTrue(selection.environment().spec().toolchains().stream()
                .anyMatch(reference -> reference.kind() == ToolchainKind.JDK
                        && reference.version().startsWith("21")));
    }
}
