package com.javaclaw.server.toolchain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;

/**
 * Turn 内部冻结的工具链选择和低信任项目声明证据；声明不能提供下载地址、可执行路径或权限。
 *
 * @param environment 精确环境版本与发行摘要
 * @param declarations 相对声明文件到 SHA-256 的映射，未发现文件不占条目
 * @param conflicts 按工具种类归属的兼容性诊断；实际执行该工具时明确失败
 * @param inspected 是否完成受限读取；旧快照和无 Coding 能力的 Turn 为 false
 * @param expectedWorkspaceRevision 普通启动需原子校验的环境版本；继承和自动化快照为空
 */
public record CodingEnvironmentSelection(
        Environment environment,
        Map<String, String> declarations,
        Map<ToolchainKind, List<String>> conflicts,
        boolean inspected,
        java.util.OptionalLong expectedWorkspaceRevision) {
    /** 固定证据集合，禁止后续修改影响已冻结 Turn。 */
    public CodingEnvironmentSelection {
        Objects.requireNonNull(environment, "environment");
        declarations = Map.copyOf(declarations);
        Objects.requireNonNull(expectedWorkspaceRevision, "expectedWorkspaceRevision");
        var copied = new java.util.EnumMap<ToolchainKind, List<String>>(ToolchainKind.class);
        conflicts.forEach((kind, values) -> copied.put(kind, List.copyOf(values)));
        conflicts = Map.copyOf(copied);
    }

    /**
     * 创建普通 Workspace 选择，提交时仍需检查配置未改变。
     *
     * @param environment 当前环境版本
     * @param declarations 声明摘要
     * @param conflicts 兼容诊断
     * @param inspected 是否完成受限读取
     */
    public CodingEnvironmentSelection(
            Environment environment,
            Map<String, String> declarations,
            Map<ToolchainKind, List<String>> conflicts,
            boolean inspected) {
        this(environment, declarations, conflicts, inspected, java.util.OptionalLong.of(environment.revision()));
    }

    /** @return 继承精确快照；不重新绑定当前 Workspace 环境版本 */
    public CodingEnvironmentSelection inherited() {
        return new CodingEnvironmentSelection(
                environment, declarations, conflicts, inspected, java.util.OptionalLong.empty());
    }

    /**
     * 检查实际将使用的种类；不会因其他语言声明冲突阻止聊天或文件工具。
     *
     * @param kinds 命令解析器从固定入口推导的工具种类
     */
    public void requireCompatible(List<ToolchainKind> kinds) {
        if (!inspected) {
            throw new IllegalStateException("TOOLCHAIN_DECLARATIONS_UNVERIFIED: 当前冻结快照没有项目声明校验证据");
        }
        for (ToolchainKind kind : kinds) {
            List<String> issues = conflicts.getOrDefault(kind, List.of());
            if (!issues.isEmpty()) {
                throw new IllegalStateException(
                        "TOOLCHAIN_DECLARATION_CONFLICT: " + kind + ": " + String.join("; ", issues));
            }
        }
    }
}
