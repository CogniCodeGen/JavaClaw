package com.javaclaw.server.turn;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ScheduledCommand;
import com.javaclaw.extension.spi.ScheduledCommandPort;

/** 启动期打破 Schedule executor 与 Extension Host 装配环的单次绑定端口。 */
public final class DeferredScheduledCommandPort implements ScheduledCommandPort {
    private final AtomicReference<ScheduledCommandPort> delegate = new AtomicReference<>();

    /**
     * 绑定唯一真实实现。
     *
     * @param port App Server 可调度命令实现
     */
    public void bind(ScheduledCommandPort port) {
        if (!delegate.compareAndSet(null, Objects.requireNonNull(port, "port"))) {
            throw new IllegalStateException("Scheduled command port is already bound");
        }
    }

    @Override
    public ExtensionResponse execute(ScheduledCommand command, CancellationToken cancellation) throws Exception {
        return requireBound().execute(command, cancellation);
    }

    private ScheduledCommandPort requireBound() {
        ScheduledCommandPort current = delegate.get();
        if (current == null) {
            throw new IllegalStateException("Scheduled command port is not bound");
        }
        return current;
    }
}
