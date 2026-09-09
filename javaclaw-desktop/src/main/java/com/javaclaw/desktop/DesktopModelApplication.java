package com.javaclaw.desktop;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.sdk.JavaClawClient;

/** 把已配置模型应用到冻结的对话；不拥有密钥，也不执行推理。 部分成功时保留新建 Thread 的身份，重试继续该对话而不重复创建。 */
final class DesktopModelApplication {
    private final Map<WorkspaceId, PendingThread> unfinished = new ConcurrentHashMap<>();

    ThreadId apply(JavaClawClient client, Target target, Consumer<DesktopConfigurationChange> changed) {
        Optional<PendingThread> pending = target.thread().isEmpty()
                ? Optional.of(unfinished.computeIfAbsent(target.workspace(), ignored -> new PendingThread()))
                : Optional.empty();
        ThreadId thread = target.thread().orElseGet(() -> pending.orElseThrow().create(client, target));
        Optional<ExecutionConfiguration> previous = client.executions().readThread(target.workspace(), thread);
        ExecutionOverrides before =
                previous.map(ExecutionConfiguration::overrides).orElseGet(ExecutionOverrides::empty);
        var reasoning = pending.map(value -> value.initial.reasoning()).orElse(before.reasoning());
        ExecutionOverrides selected = DesktopModelPreferences.replace(before, Optional.of(target.model()), reasoning);
        ExecutionPreview preview = client.executions().preview(target.workspace(), Optional.of(thread), selected);
        requireSelection(preview, target.model());
        if (!selected.equals(before)) {
            client.executions()
                    .updateThread(
                            target.workspace(),
                            thread,
                            selected,
                            CommandOptions.create(previous.map(ExecutionConfiguration::revision)
                                    .orElse(0L)));
            changed.accept(new DesktopConfigurationChange(
                    DesktopConfigurationChange.Kind.EXECUTION, Optional.of(target.workspace()), Optional.of(thread)));
        }
        requireSelection(
                client.executions().preview(target.workspace(), Optional.of(thread), ExecutionOverrides.empty()),
                target.model());
        remember(client, target.model(), changed);
        return thread;
    }

    private static void remember(
            JavaClawClient client, ProviderRef provider, Consumer<DesktopConfigurationChange> changed) {
        try {
            if (DesktopModelPreferences.rememberModel(client, provider)) {
                changed.accept(new DesktopConfigurationChange(
                        DesktopConfigurationChange.Kind.EXECUTION, Optional.empty(), Optional.empty()));
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("模型已用于当前对话，但日常默认保存失败；请重试。" + DesktopFailures.safeMessage(failure), failure);
        }
    }

    void complete(Target target) {
        if (target.thread().isEmpty()) {
            unfinished.remove(target.workspace());
        }
    }

    private static void requireSelection(ExecutionPreview preview, ProviderRef selected) {
        if (preview.modelLocked()
                && !preview.provider().filter(selected::equals).isPresent()) {
            throw new IllegalStateException("当前 Agent 固定了其他模型，请先在更多设置中选择 Agent");
        }
        if (!preview.ready()) {
            throw new IllegalStateException(preview.blockers().getFirst().message());
        }
        if (!preview.provider().filter(selected::equals).isPresent()) {
            throw new IllegalStateException("模型选择已被其他修改更新，请重试应用");
        }
    }

    private static final class PendingThread {
        private final CommandOptions createOptions = CommandOptions.create(0);
        private ExecutionOverrides initial;
        private ThreadId thread;

        synchronized ThreadId create(JavaClawClient client, Target target) {
            if (initial == null) {
                initial = DesktopModelPreferences.latest(client);
            }
            if (thread == null) {
                ExecutionOverrides selected =
                        DesktopModelPreferences.replace(initial, Optional.of(target.model()), initial.reasoning());
                requireSelection(
                        client.executions().preview(target.workspace(), Optional.empty(), selected), target.model());
                thread = client.threads()
                        .create(target.workspace(), "新对话", createOptions)
                        .id();
            }
            return thread;
        }
    }

    /** @param workspace 非空目标工作区 @param thread 可选固定对话 @param model 非空精确模型 */
    record Target(WorkspaceId workspace, Optional<ThreadId> thread, ProviderRef model) {
        Target {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(model, "model");
        }
    }
}
