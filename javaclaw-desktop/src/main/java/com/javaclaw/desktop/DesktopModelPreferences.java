package com.javaclaw.desktop;

import java.util.Optional;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.sdk.JavaClawClient;

/** 只通过 SDK 合并模型偏好；任何合并都保留角色、权限、审批、预算和能力字段。 */
final class DesktopModelPreferences {
    private DesktopModelPreferences() {}

    static ExecutionOverrides replace(
            ExecutionOverrides current, Optional<ProviderRef> provider, Optional<ReasoningPreference> reasoning) {
        return new ExecutionOverrides(current.role(), provider, current.permissionProfile(), current.approvalPolicy(),
                current.budget(), current.visibleCapabilities(), reasoning);
    }

    static ExecutionOverrides models(ExecutionOverrides value) {
        return replace(ExecutionOverrides.empty(), value.provider(), value.reasoning());
    }

    static void remember(JavaClawClient client, ExecutionOverrides selected) {
        Optional<ExecutionConfiguration> current = client.executions().readDefaults(Optional.empty());
        ExecutionOverrides before = current.map(ExecutionConfiguration::overrides).orElseGet(ExecutionOverrides::empty);
        ExecutionOverrides merged = replace(before, selected.provider(), selected.reasoning());
        if (!before.equals(merged)) {
            client.executions().updateDefaults(Optional.empty(), merged,
                    CommandOptions.create(current.map(ExecutionConfiguration::revision).orElse(0L)));
        }
    }

    static ConversationThread createThread(JavaClawClient client, WorkspaceId workspace, String title) {
        ExecutionOverrides initial = client.executions().readDefaults(Optional.empty())
                .map(ExecutionConfiguration::overrides).map(DesktopModelPreferences::models)
                .orElseGet(ExecutionOverrides::empty);
        ConversationThread created = client.threads().create(workspace, title, CommandOptions.create(0));
        if (!initial.equals(ExecutionOverrides.empty())) {
            client.executions().updateThread(workspace, created.id(), initial, CommandOptions.create(0));
        }
        return created;
    }
}
