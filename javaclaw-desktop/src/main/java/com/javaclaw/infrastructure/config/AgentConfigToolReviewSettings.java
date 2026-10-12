package com.javaclaw.infrastructure.config;

import com.javaclaw.application.chat.ToolReviewSettingsPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/** 通过现有工作区 AgentConfig 适配工具审核策略端口。 */
public final class AgentConfigToolReviewSettings
        implements ToolReviewSettingsPort, AutoCloseable {

    private final TaskScope persistence;
    private final AgentConfig config;
    private CompletionStage<Void> lastSave = CompletableFuture.completedFuture(null);
    private boolean closed;

    public AgentConfigToolReviewSettings(ManagedTaskExecutor tasks, AgentConfig config) {
        persistence = Objects.requireNonNull(tasks, "tasks")
                .openScope("tool-review-settings", 1);
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public ToolReviewMode current() {
        return config.getToolReviewMode();
    }

    @Override
    public synchronized CompletionStage<Void> update(ToolReviewMode mode) {
        if (closed) throw new RejectedExecutionException("工具审核设置已关闭");
        config.setToolReviewMode(mode == null ? ToolReviewMode.SMART : mode);
        Runnable save = config.prepareToolReviewModeSave();
        // 后一次保存必须等前一次结算，防止虚拟线程的实际启动顺序覆盖最新值。
        lastSave = lastSave.handle((ignored, failure) -> null).thenCompose(ignored -> {
            try {
                return persistence.<Void>submit(TaskSpec.io("tool-review-mode-save"), context -> {
                    save.run();
                    return null;
                }).completion();
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
        return lastSave.toCompletableFuture().minimalCompletionStage();
    }

    @Override
    public void close() {
        synchronized (this) { closed = true; }
        persistence.close();
    }
}
