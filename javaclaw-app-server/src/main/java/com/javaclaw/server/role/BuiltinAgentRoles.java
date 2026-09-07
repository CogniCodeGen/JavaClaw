package com.javaclaw.server.role;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.RoleLifecycle;

/** 发行资源中的内置 Role；固定内容摘要确保首次写入和重启读取保持一致。 */
public final class BuiltinAgentRoles {
    private static final List<RoleResource> RESOURCES = List.of(
            new RoleResource(
                    "default",
                    "通用任务的默认角色，不预设任务领域。",
                    "00947186a5562c07e858836240860675ead622fd12c335ebe76684e178f8c188"),
            new RoleResource(
                    "worker",
                    "在明确所有权和有限范围内完成执行任务。",
                    "a136db26b7b0c74a8af7338587806f9aa2fbd34a862dc2507add90b23de46d27"),
            new RoleResource(
                    "explorer",
                    "只读调查具体问题，区分事实、推断与未知。",
                    "147ec3b0c07d7ce1df89d9013a20c33f18e3bd12ef863f3a779712f7c776113f"),
            new RoleResource(
                    "software-engineer",
                    "完成软件开发、测试和代码审查，保留项目设计体系。",
                    "b21511e374790f95339a0e48e9ead57a9494522b5733a67db3d81ed18e75a11b"));

    private final List<AgentRole> roles;

    /** 读取全部内置角色并验证 SHA-256；缺失或内容变化时拒绝启动。 */
    public BuiltinAgentRoles() {
        roles = RESOURCES.stream().map(BuiltinAgentRoles::load).toList();
    }

    /** @return 按发行版顺序排列的四个精确内置版本 */
    public List<AgentRole> list() {
        return roles;
    }

    /**
     * 判断标识是否被内置角色保留。
     *
     * @param id Role 标识
     * @return 是否为内置保留标识
     */
    public static boolean contains(String id) {
        return RESOURCES.stream().anyMatch(resource -> resource.id().equals(id));
    }

    private static AgentRole load(RoleResource resource) {
        String instruction = read("/prompts/role-" + resource.id() + "-v1.txt");
        if (!resource.digest().equals(digest(instruction))) {
            throw new IllegalStateException("内置 Role 摘要不匹配: " + resource.id());
        }
        // Explorer 的只读边界是服务端数据契约，提示词不承担权限控制。
        PermissionConstraint constraint =
                resource.id().equals("explorer") ? PermissionConstraint.READ_ONLY : PermissionConstraint.INHERIT;
        AgentRoleSpec spec = new AgentRoleSpec(
                resource.id(),
                resource.description(),
                instruction,
                Optional.empty(),
                Optional.empty(),
                CapabilityNarrowing.inherit(),
                constraint,
                Map.of());
        return new AgentRole(resource.id(), 1, RoleLifecycle.ACTIVE, spec, true, Instant.EPOCH, Instant.EPOCH);
    }

    private static String read(String path) {
        try (InputStream input = BuiltinAgentRoles.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("缺少内置 Role 资源: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取内置 Role 资源: " + path, failure);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private record RoleResource(String id, String description, String digest) {}
}
