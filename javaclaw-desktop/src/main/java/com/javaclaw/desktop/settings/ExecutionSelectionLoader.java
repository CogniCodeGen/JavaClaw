package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.Workspace;

/** 在固定作用域内通过 SDK 读取完整展示快照；精确继承角色与目录一起完成，避免半加载的模型锁定。 */
final class ExecutionSelectionLoader {
    private final CoreSettingsGateway gateway;

    ExecutionSelectionLoader(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    CompletionStage<Snapshot> load(Scope scope) {
        CompletionStage<Catalog> catalog = gateway.roles()
                .thenCombine(gateway.providers(), Catalog::new)
                .thenCombine(gateway.permissionProfiles(), Catalog::withPermissions);
        return catalog.thenCombine(configuration(scope), Loaded::new)
                .thenCompose(loaded -> inherited(scope, loaded)
                        .thenApply(role -> new Snapshot(loaded.catalog(), loaded.sources(), role)));
    }

    private CompletionStage<Sources> configuration(Scope scope) {
        CompletionStage<Optional<ExecutionConfiguration>> installation = gateway.executionDefaults(Optional.empty());
        CompletionStage<Optional<ExecutionConfiguration>> workspace = scope.workspace()
                .map(value -> gateway.executionDefaults(Optional.of(value.id())))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
        CompletionStage<Optional<ExecutionConfiguration>> thread = scope.thread()
                .map(value -> gateway.threadExecution(value.workspaceId(), value.id()))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
        return installation.thenCombine(workspace, Sources::new).thenCombine(thread, Sources::withThread);
    }

    private CompletionStage<Optional<AgentRole>> inherited(Scope scope, Loaded loaded) {
        Sources sources = loaded.sources();
        Optional<AgentRoleRef> installation =
                sources.installation().flatMap(value -> value.overrides().role());
        Optional<AgentRoleRef> reference = scope.defaults()
                ? installation
                : sources.thread()
                        .flatMap(value -> value.overrides().role())
                        .or(() -> sources.workspace()
                                .flatMap(value -> value.overrides().role()))
                        .or(() -> installation);
        if (reference.isPresent()) {
            return gateway.role(reference.orElseThrow()).thenApply(Optional::of);
        }
        return CompletableFuture.completedFuture(loaded.catalog().roles().stream()
                .filter(role -> role.id().equals("default"))
                .findFirst());
    }

    static String describe(String name, Optional<ExecutionConfiguration> configuration) {
        return configuration
                .map(value -> name + " · r" + value.revision() + " · Agent "
                        + value.overrides()
                                .role()
                                .map(role -> role.id() + "@" + role.revision())
                                .orElse("继承")
                        + " · 模型 "
                        + value.overrides()
                                .provider()
                                .map(provider -> provider.model() + " / " + provider.endpointId())
                                .orElse("继承")
                        + " · 权限 "
                        + value.overrides()
                                .permissionProfile()
                                .map(permission -> permission.id() + "@" + permission.version())
                                .orElse("继承")
                        + " · 推理 "
                        + value.overrides().reasoning().map(Enum::name).orElse("继承"))
                .orElse(name + " · 未设置，继承上级");
    }

    /**
     * 固定的一次读取作用域。
     *
     * @param workspace 非空容器，缺省表示不读取 Workspace 层
     * @param thread 非空容器，缺省表示不读取 Thread 层
     * @param defaults 是否编辑 Workspace 直接默认值，而非单次执行覆盖
     */
    record Scope(Optional<Workspace> workspace, Optional<ConversationThread> thread, boolean defaults) {
        Scope {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(thread, "thread");
        }

        boolean sameBinding(Scope other) {
            return workspace.map(Workspace::id).equals(other.workspace.map(Workspace::id))
                    && thread.map(ConversationThread::id).equals(other.thread.map(ConversationThread::id))
                    && defaults == other.defaults;
        }
    }

    /**
     * 同次加载的可选择目录，所有集合非空且可以为空集合。
     *
     * @param roles 最新角色目录
     * @param providers 最新 Provider 目录
     * @param permissions 权限配置目录
     */
    record Catalog(List<AgentRole> roles, List<ProviderEndpoint> providers, List<PermissionProfile> permissions) {
        private Catalog(List<AgentRole> roles, List<ProviderEndpoint> providers) {
            this(roles, providers, List.of());
        }

        private Catalog withPermissions(List<PermissionProfile> permissions) {
            return new Catalog(roles, providers, permissions);
        }
    }

    /**
     * 各层直接配置；容器非空，未配置的层使用空 Optional。
     *
     * @param installation 安装级配置
     * @param workspace 当前 Workspace 配置
     * @param thread 当前 Thread 配置
     */
    record Sources(
            Optional<ExecutionConfiguration> installation,
            Optional<ExecutionConfiguration> workspace,
            Optional<ExecutionConfiguration> thread) {
        private Sources(Optional<ExecutionConfiguration> installation, Optional<ExecutionConfiguration> workspace) {
            this(installation, workspace, Optional.empty());
        }

        private Sources withThread(Optional<ExecutionConfiguration> thread) {
            return new Sources(installation, workspace, thread);
        }

        String description() {
            return describe("安装", installation) + "\n" + describe("Workspace", workspace) + "\n"
                    + describe("Thread", thread);
        }
    }

    /**
     * 同一读取链的完整展示数据。
     *
     * @param catalog 非空选择目录
     * @param sources 非空分层配置
     * @param inheritedRole 非空容器，缺省表示没有可显示的继承角色
     */
    record Snapshot(Catalog catalog, Sources sources, Optional<AgentRole> inheritedRole) {}

    /**
     * 精确角色读取前已完成的快照。
     *
     * @param catalog 非空选择目录
     * @param sources 非空分层配置
     */
    private record Loaded(Catalog catalog, Sources sources) {}
}
