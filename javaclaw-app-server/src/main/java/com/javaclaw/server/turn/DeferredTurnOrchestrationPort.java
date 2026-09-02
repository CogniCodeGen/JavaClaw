package com.javaclaw.server.turn;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.extension.spi.OrchestratedTurnCommand;
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.TurnOrchestrationPort;

/** 启动期打破 Extension Host 与 Harness 调度器装配环的单次绑定端口。 */
public final class DeferredTurnOrchestrationPort implements TurnOrchestrationPort {
    private final AtomicReference<TurnOrchestrationPort> delegate = new AtomicReference<>();

    /**
     * 绑定唯一真实实现。
     *
     * @param port App Server Turn 编排实现
     */
    public void bind(TurnOrchestrationPort port) {
        if (!delegate.compareAndSet(null, Objects.requireNonNull(port, "port"))) {
            throw new IllegalStateException("Turn orchestration port is already bound");
        }
    }

    @Override
    public OrchestratedTurnResult execute(OrchestratedTurnCommand command, CancellationToken cancellation)
            throws Exception {
        TurnOrchestrationPort current = delegate.get();
        if (current == null) {
            throw new IllegalStateException("Turn orchestration port is not bound");
        }
        return current.execute(command, cancellation);
    }
}
