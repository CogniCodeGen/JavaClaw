package com.javaclaw.desktop;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.sdk.JavaClawClient;

/** 日常偏好只保存模型与思考，不参与已有对话的继承链；初始化新对话时保留其其他执行限制。 */
final class DesktopModelPreferences {
    private DesktopModelPreferences() {}

    static ExecutionOverrides replace(
            ExecutionOverrides current, Optional<ProviderRef> provider, Optional<ReasoningPreference> reasoning) {
        return new ExecutionOverrides(
                current.role(),
                provider,
                current.permissionProfile(),
                current.approvalPolicy(),
                current.budget(),
                current.visibleCapabilities(),
                reasoning);
    }

    static ExecutionOverrides models(ExecutionOverrides value) {
        return replace(ExecutionOverrides.empty(), value.provider(), value.reasoning());
    }

    static boolean remember(JavaClawClient client, ExecutionOverrides selected) {
        Optional<ExecutionConfiguration> current = client.executions().readRecent();
        ExecutionOverrides before =
                current.map(ExecutionConfiguration::overrides).orElseGet(ExecutionOverrides::empty);
        ExecutionOverrides merged = models(selected);
        if (!before.equals(merged)) {
            client.executions()
                    .updateRecent(
                            merged,
                            CommandOptions.create(current.map(ExecutionConfiguration::revision)
                                    .orElse(0L)));
            return true;
        }
        return false;
    }

    static boolean rememberModel(JavaClawClient client, ProviderRef provider) {
        Optional<ExecutionConfiguration> current = client.executions().readRecent();
        ExecutionOverrides before =
                current.map(ExecutionConfiguration::overrides).orElseGet(ExecutionOverrides::empty);
        ExecutionOverrides merged = replace(ExecutionOverrides.empty(), Optional.of(provider), before.reasoning());
        if (!before.equals(merged)) {
            client.executions()
                    .updateRecent(
                            merged,
                            CommandOptions.create(current.map(ExecutionConfiguration::revision)
                                    .orElse(0L)));
            return true;
        }
        return false;
    }

    static ExecutionOverrides latest(JavaClawClient client) {
        return client.executions()
                .readRecent()
                .map(ExecutionConfiguration::overrides)
                .map(DesktopModelPreferences::models)
                .orElseGet(ExecutionOverrides::empty);
    }

    /** 每个 Desktop 持有独立缓存；失败重试复用身份与冻结默认值，不把部分成功当成新建请求。 */
    static final class ThreadCreations {
        private final Map<CreationTarget, PendingCreation> pending = new ConcurrentHashMap<>();

        ConversationThread create(JavaClawClient client, WorkspaceId workspace, String title) {
            CreationTarget target = new CreationTarget(workspace, title);
            PendingCreation creation = pending.computeIfAbsent(target, ignored -> new PendingCreation());
            ConversationThread result = creation.create(client, target);
            pending.remove(target, creation);
            return result;
        }
    }

    private record CreationTarget(WorkspaceId workspace, String title) {
        private CreationTarget {
            Objects.requireNonNull(workspace, "workspace");
            title = Objects.requireNonNull(title, "title").strip();
        }
    }

    private static final class PendingCreation {
        private final CommandOptions createOptions = CommandOptions.create(0);
        private ExecutionOverrides initial;
        private ConversationThread created;
        private ExecutionOverrides replacement;
        private CommandOptions updateOptions;

        synchronized ConversationThread create(JavaClawClient client, CreationTarget target) {
            if (initial == null) {
                initial = latest(client);
            }
            if (created == null) {
                created = client.threads().create(target.workspace(), target.title(), createOptions);
            }
            if (!initial.equals(ExecutionOverrides.empty())) {
                initialize(client, target.workspace());
            }
            return created;
        }

        private void initialize(JavaClawClient client, WorkspaceId workspace) {
            if (updateOptions == null) {
                Optional<ExecutionConfiguration> current = client.executions().readThread(workspace, created.id());
                ExecutionOverrides before =
                        current.map(ExecutionConfiguration::overrides).orElseGet(ExecutionOverrides::empty);
                replacement = replace(before, initial.provider(), initial.reasoning());
                updateOptions = CommandOptions.create(
                        current.map(ExecutionConfiguration::revision).orElse(0L));
            }
            client.executions().updateThread(workspace, created.id(), replacement, updateOptions);
        }
    }
}
