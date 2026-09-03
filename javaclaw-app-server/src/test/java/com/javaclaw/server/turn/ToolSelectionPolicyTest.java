package com.javaclaw.server.turn;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSelectionPolicyTest {
    private static final Path WORKSPACE = Path.of("/workspace");

    @Test
    void 设置候选不需要先进入精确工具白名单() {
        PermissionProfile review = profile(
                new FilePermission(List.of(WORKSPACE), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                ToolRisk.READ_ONLY);

        assertTrue(ToolSelectionPolicy.allows(tool("inspect", ToolRisk.READ_ONLY), review));
        assertFalse(ToolSelectionPolicy.allows(tool("write", ToolRisk.WORKSPACE_WRITE), review));
    }

    @Test
    void 开发候选按文件网络与进程基础能力收窄() {
        PermissionProfile developer = profile(
                new FilePermission(List.of(WORKSPACE), List.of(WORKSPACE), true, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofMinutes(5)),
                ToolRisk.PROCESS);

        assertTrue(ToolSelectionPolicy.allows(tool("write", ToolRisk.WORKSPACE_WRITE), developer));
        assertFalse(ToolSelectionPolicy.allows(tool("network", ToolRisk.NETWORK), developer));
        assertFalse(ToolSelectionPolicy.allows(tool("process", ToolRisk.PROCESS), developer));
        assertFalse(ToolSelectionPolicy.allows(tool("effect", ToolRisk.EXTERNAL_EFFECT), developer));

        PermissionProfile enabled = profile(
                developer.files(),
                new NetworkPermission(Set.of("example.test"), Set.of(443), true),
                new ProcessPermission(Set.of("git"), false, Duration.ofMinutes(5)),
                ToolRisk.PROCESS);
        assertTrue(ToolSelectionPolicy.allows(tool("network", ToolRisk.NETWORK), enabled));
        assertTrue(ToolSelectionPolicy.allows(tool("process", ToolRisk.PROCESS), enabled));
    }

    private static PermissionProfile profile(
            FilePermission files, NetworkPermission network, ProcessPermission process, ToolRisk maximumRisk) {
        return new PermissionProfile(
                "setup",
                1,
                files,
                network,
                process,
                new ToolPermission(Set.of(), maximumRisk, ApprovalRequirement.RISKY),
                new ResourceLimits(1024, 1024, 1, 1));
    }

    private static ToolDescriptor tool(String name, ToolRisk risk) {
        CanonicalJson json = new CanonicalJson();
        return new ToolDescriptor(
                new ToolIdentity("test", name, 1),
                name,
                json.parse("{\"type\":\"object\"}"),
                json.parse("{\"type\":\"object\"}"),
                risk,
                Set.of());
    }
}
