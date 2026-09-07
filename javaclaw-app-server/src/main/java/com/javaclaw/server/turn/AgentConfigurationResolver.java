package com.javaclaw.server.turn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.ConfigurationProvenance;
import com.javaclaw.api.ConfigurationSource;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionResolver;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResolvedTurnConfig;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExecutionConfigurationService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.PersistenceException;

/**
 * App Server 唯一的执行配置解析器。
 *
 * <p>Role 不携带授权。所有能力在系统、Workspace、冻结权限、实时撤权及父 Turn 上限之间求交；Harness 只接收结果。
 */
public final class AgentConfigurationResolver {
    private final AgentRoleService roles;
    private final ExecutionConfigurationService configurations;
    private final PermissionProfileService permissions;
    private final CoreCommandService core;

    /**
     * 创建解析器，所有依赖均为 App Server 的权威服务。
     *
     * @param roles 版本化角色仓储服务
     * @param configurations 安装、Workspace 与 Thread 配置服务
     * @param permissions 即时权限求交服务
     * @param core Thread 归属查询服务
     */
    public AgentConfigurationResolver(
            AgentRoleService roles,
            ExecutionConfigurationService configurations,
            PermissionProfileService permissions,
            CoreCommandService core) {
        this.roles = Objects.requireNonNull(roles, "roles");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.core = Objects.requireNonNull(core, "core");
    }

    ResolvedAgentConfiguration resolve(
            ThreadExecutionScope scope, Optional<ThreadId> thread, ExecutionOverrides explicit) {
        ConfigurationSelection selection = new ConfigurationSelection();
        configurations.findForTurn(scope.workspace().id(), thread).forEach(value -> apply(selection, value, ""));
        selection.apply(explicit, ConfigurationSource.TURN, "explicit-selection", 0);
        return finish(selection, scope, Optional.empty(), Optional.empty());
    }

    ResolvedAgentConfiguration resolveChild(
            ThreadExecutionScope scope,
            ResolvedTurnConfig parent,
            ExecutionOverrides spawn,
            PermissionProfile parentPermissions) {
        ConfigurationSelection defaults = new ConfigurationSelection();
        configurations
                .findSubagentDefaultsForTurn(
                        scope.workspace().id(), scope.thread().map(value -> value.id()))
                .forEach(value -> apply(defaults, value, "subagent:"));
        ExecutionOverrides selected = new ExecutionOverrides(
                Optional.empty(),
                Optional.ofNullable(defaults.provider),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                defaults.reasoning);
        ResolvedAgentConfiguration result = resolveChild(scope, parent, spawn, selected, parentPermissions);
        List<ConfigurationProvenance> sources = new ArrayList<>();
        for (ConfigurationProvenance provenance : result.provenance()) {
            if (provenance.sourceId().equals("subagent-defaults")) {
                sources.addAll(defaults.provenance.stream()
                        .filter(value -> value.field().equals(provenance.field()))
                        .toList());
            } else {
                sources.add(provenance);
            }
        }
        return new ResolvedAgentConfiguration(
                result.role(),
                result.provider(),
                result.permissionProfile(),
                result.approvalPolicy(),
                result.budget(),
                result.effectivePermissions(),
                result.permissionConstraint(),
                result.effectiveSkills(),
                result.reasoning(),
                sources);
    }

    ResolvedAgentConfiguration resolveChild(
            ThreadExecutionScope scope,
            ResolvedTurnConfig parent,
            ExecutionOverrides spawn,
            ExecutionOverrides childDefaults,
            PermissionProfile parentPermissions) {
        ConfigurationSelection selection = new ConfigurationSelection();
        selection.budget = parent.budget();
        selection.apply(overrides(parent), ConfigurationSource.PARENT, "parent-turn", 0);
        // 子默认只影响模型与推理；安全边界仍来自父 Turn，不能由子默认替换权限。
        selection.apply(
                new ExecutionOverrides(
                        Optional.empty(),
                        childDefaults.provider(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        childDefaults.reasoning()),
                ConfigurationSource.INSTALLATION,
                "subagent-defaults",
                0);
        selection.apply(spawn, ConfigurationSource.TURN, "spawn-selection", 0);
        PermissionConstraint parentConstraint = parent.permissionConstraint();
        ResolvedAgentConfiguration result =
                finish(selection, scope, Optional.of(parentPermissions), parent.effectiveSkills());
        if (parentConstraint == PermissionConstraint.READ_ONLY && result.permissionConstraint() != parentConstraint) {
            return readOnlyChild(result);
        }
        return result;
    }

    PermissionProfile restorePermissions(ResolvedTurnConfig frozen, ThreadExecutionScope scope) {
        PermissionProfile current = permissions.resolveForExecution(
                frozen.permissionProfile().id(),
                frozen.permissionProfile().version(),
                scope.workspace(),
                scope.root(),
                scope.writable());
        return constrain(
                current,
                Optional.of(frozen.effectiveCapabilities()),
                frozen.approvalPolicy(),
                frozen.permissionConstraint());
    }

    private static void apply(ConfigurationSelection selection, ExecutionConfiguration value, String prefix) {
        ConfigurationSource source = value.threadId().isPresent()
                ? ConfigurationSource.THREAD
                : value.workspaceId().isPresent() ? ConfigurationSource.WORKSPACE : ConfigurationSource.INSTALLATION;
        String id = prefix
                + value.threadId()
                        .map(Object::toString)
                        .orElseGet(
                                () -> value.workspaceId().map(Object::toString).orElse("installation"));
        selection.apply(value.overrides(), source, id, value.revision());
    }

    private ResolvedAgentConfiguration finish(
            ConfigurationSelection selection,
            ThreadExecutionScope scope,
            Optional<PermissionProfile> parent,
            Optional<Set<String>> parentSkills) {
        AgentRole role = roles.requireAvailable(selection.role.id(), selection.role.revision());
        role.spec().model().ifPresent(value -> {
            selection.provider = value.provider();
            selection.provenance.add(
                    new ConfigurationProvenance("provider", ConfigurationSource.ROLE, role.id(), role.revision()));
        });
        role.spec().reasoning().ifPresent(value -> {
            selection.reasoning = Optional.of(value);
            selection.provenance.add(
                    new ConfigurationProvenance("reasoning", ConfigurationSource.ROLE, role.id(), role.revision()));
        });
        if (selection.provider == null) {
            throw PersistenceException.invalidRequest("尚未选择模型，请先配置执行默认值或在本次 Turn 中选择 Provider/模型");
        }
        PermissionConstraint constraint = roleConstraint(role);
        PermissionProfile current = permissions.resolveForExecution(
                selection.permission.id(),
                selection.permission.version(),
                scope.workspace(),
                scope.root(),
                scope.writable());
        if (parent.isPresent()) {
            current = PermissionResolver.intersect(List.of(current, parent.orElseThrow()));
        }
        Optional<Set<String>> capabilities = ConfigurationSelection.narrow(
                selection.capabilities, role.spec().narrowing().capabilities());
        PermissionProfile effective = constrain(current, capabilities, selection.approval, constraint);
        ApprovalPolicy approval =
                ApprovalPolicy.valueOf(effective.tools().approvalRequirement().name());
        List<ConfigurationProvenance> provenance = new ArrayList<>(selection.provenance);
        provenance.add(
                new ConfigurationProvenance("capabilities", ConfigurationSource.ROLE, role.id(), role.revision()));
        provenance.add(new ConfigurationProvenance(
                "approvalPolicy", ConfigurationSource.SYSTEM, "permission-intersection", effective.version()));
        return new ResolvedAgentConfiguration(
                role,
                selection.provider,
                selection.permission,
                approval,
                selection.budget,
                effective,
                constraint,
                ConfigurationSelection.narrow(
                        parentSkills, role.spec().narrowing().skills()),
                selection.reasoning,
                List.copyOf(provenance));
    }

    /**
     * 为管理预览和执行链共用权限收窄规则；该方法不能授予任何权限。
     *
     * @param current 已求交的系统、Workspace 和 PermissionProfile 权限
     * @param capabilities 可选能力名称上限，空集合禁用全部
     * @param approval 额外最低审批要求
     * @param constraint Role 或父 Turn 的只读上限
     * @return 等于或窄于输入权限的新不可变配置
     */
    public static PermissionProfile constrain(
            PermissionProfile current,
            Optional<Set<String>> capabilities,
            ApprovalPolicy approval,
            PermissionConstraint constraint) {
        Set<String> allowed = new HashSet<>(current.tools().allowedTools());
        capabilities.ifPresent(allowed::retainAll);
        ApprovalRequirement required = ApprovalRequirement.valueOf(approval.name());
        if (required.ordinal() < current.tools().approvalRequirement().ordinal()) {
            required = current.tools().approvalRequirement();
        }
        boolean readOnly = constraint == PermissionConstraint.READ_ONLY;
        return new PermissionProfile(
                current.id(),
                current.version(),
                readOnly ? new FilePermission(current.files().readRoots(), List.of(), false, false) : current.files(),
                readOnly ? new NetworkPermission(Set.of(), Set.of(), true) : current.network(),
                readOnly
                        ? new ProcessPermission(
                                Set.of(), false, current.processes().maxRunTime())
                        : current.processes(),
                new ToolPermission(
                        allowed, readOnly ? ToolRisk.READ_ONLY : current.tools().maximumRisk(), required),
                current.resources());
    }

    /**
     * 读取 Role 权限上限，内置 explorer 始终由代码施加只读边界。
     *
     * @param role 已验证的精确角色版本
     * @return 仅继承或只读，不携带授权
     */
    public static PermissionConstraint roleConstraint(AgentRole role) {
        return role.id().equals("explorer")
                ? PermissionConstraint.READ_ONLY
                : role.spec().permissionConstraint();
    }

    private static ResolvedAgentConfiguration readOnlyChild(ResolvedAgentConfiguration value) {
        return new ResolvedAgentConfiguration(
                value.role(),
                value.provider(),
                value.permissionProfile(),
                value.approvalPolicy(),
                value.budget(),
                constrain(
                        value.effectivePermissions(),
                        Optional.empty(),
                        value.approvalPolicy(),
                        PermissionConstraint.READ_ONLY),
                PermissionConstraint.READ_ONLY,
                value.effectiveSkills(),
                value.reasoning(),
                value.provenance());
    }

    static ExecutionOverrides overrides(ResolvedTurnConfig frozen) {
        return new ExecutionOverrides(
                Optional.of(frozen.role()),
                Optional.of(frozen.provider()),
                Optional.of(frozen.permissionProfile()),
                Optional.of(frozen.approvalPolicy()),
                Optional.of(frozen.budget()),
                Optional.of(frozen.effectiveCapabilities()),
                frozen.reasoning());
    }
}
