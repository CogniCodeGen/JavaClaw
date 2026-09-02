package com.javaclaw.server.turn;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.extension.spi.AutomationStepPort;

/** 启动期打破 Workflow executor 与工具平台装配环的单次绑定端口。 */
public final class DeferredAutomationStepPort implements AutomationStepPort {
    private final AtomicReference<AutomationStepPort> delegate = new AtomicReference<>();

    /**
     * 绑定唯一真实实现。
     *
     * @param port App Server 治理步骤实现
     */
    public void bind(AutomationStepPort port) {
        if (!delegate.compareAndSet(null, Objects.requireNonNull(port, "port"))) {
            throw new IllegalStateException("Automation step port is already bound");
        }
    }

    @Override
    public ToolResult executeTool(ToolCommand command, CancellationToken cancellation) throws Exception {
        return requireBound().executeTool(command, cancellation);
    }

    @Override
    public InputResult openInput(InputCommand command, CancellationToken cancellation) throws Exception {
        return requireBound().openInput(command, cancellation);
    }

    private AutomationStepPort requireBound() {
        AutomationStepPort current = delegate.get();
        if (current == null) {
            throw new IllegalStateException("Automation step port is not bound");
        }
        return current;
    }
}
