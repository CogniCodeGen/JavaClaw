package com.javaclaw.desktop;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.sdk.JavaClawClient;

/**
 * 把已配置模型应用到冻结的对话；不拥有密钥，也不执行推理。
 * 部分成功时保留新建 Thread 的身份，重试继续该对话而不重复创建。
 */
final class DesktopModelApplication {
    private final Map<Target, ThreadId> unfinished = new ConcurrentHashMap<>();

    ThreadId apply(JavaClawClient client, Target target) {
        ThreadId thread = target.thread().orElseGet(() -> unfinished.computeIfAbsent(target,
                ignored -> client.threads().create(target.workspace(), "新对话", CommandOptions.create(0)).id()));
        Optional<ExecutionConfiguration> previous = client.executions().readThread(target.workspace(), thread);
        ExecutionOverrides before = previous.map(ExecutionConfiguration::overrides).orElseGet(ExecutionOverrides::empty);
        ExecutionOverrides selected = DesktopModelPreferences.replace(before, Optional.of(target.model()), before.reasoning());
        ExecutionPreview preview = client.executions().preview(target.workspace(), Optional.of(thread), selected);
        requireSelection(preview, target.model());
        DesktopModelPreferences.remember(client, selected);
        if (!selected.equals(before)) {
            client.executions().updateThread(target.workspace(), thread, selected,
                    CommandOptions.create(previous.map(ExecutionConfiguration::revision).orElse(0L)));
        }
        requireSelection(client.executions().preview(target.workspace(), Optional.of(thread), ExecutionOverrides.empty()),
                target.model());
        return thread;
    }

    void complete(Target target) {
        unfinished.remove(target);
    }

    private static void requireSelection(ExecutionPreview preview, ProviderRef selected) {
        if (preview.modelLocked() && !preview.provider().filter(selected::equals).isPresent()) {
            throw new IllegalStateException("当前 Agent 固定了其他模型，请先在更多设置中选择 Agent");
        }
        if (!preview.ready()) {
            throw new IllegalStateException(preview.blockers().getFirst().message());
        }
    }

    /** @param workspace 非空目标工作区 @param thread 可选固定对话 @param model 非空精确模型 */
    record Target(WorkspaceId workspace, Optional<ThreadId> thread, ProviderRef model) {}
}
