package com.javaclaw.infrastructure.config;

import com.javaclaw.application.chat.ToolReviewSettingsPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;

import java.util.Objects;

/** 通过现有工作区 AgentConfig 适配工具审核策略端口。 */
public final class AgentConfigToolReviewSettings
        implements ToolReviewSettingsPort, AutoCloseable {

    private final TaskScope persistence;
    private final AgentConfig config;

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
    public void update(ToolReviewMode mode) {
        ToolReviewMode resolved = mode == null ? ToolReviewMode.SMART : mode;
        config.setToolReviewMode(resolved);
        config.saveToolReviewModeAsync(command -> persistence.submit(
                TaskSpec.io("tool-review-mode-save"), context -> {
                    command.run();
                    return null;
                }));
    }

    @Override
    public void close() {
        persistence.close();
    }
}
