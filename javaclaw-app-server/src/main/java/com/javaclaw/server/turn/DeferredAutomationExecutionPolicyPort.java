package com.javaclaw.server.turn;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.AutomationExecutionPolicyPort;
import com.javaclaw.extension.spi.AutomationProfileOption;

/** 启动期打破 Extension Host 与工具平台装配环的单次绑定自动化策略端口。 */
public final class DeferredAutomationExecutionPolicyPort implements AutomationExecutionPolicyPort {
    private final AtomicReference<AutomationExecutionPolicyPort> delegate = new AtomicReference<>();

    /**
     * 绑定唯一真实实现。
     *
     * @param port App Server 权威自动化策略实现
     */
    public void bind(AutomationExecutionPolicyPort port) {
        if (!delegate.compareAndSet(null, Objects.requireNonNull(port, "port"))) {
            throw new IllegalStateException("Automation execution policy port is already bound");
        }
    }

    @Override
    public List<AutomationProfileOption> profiles(WorkspaceId workspaceId) {
        return requireDelegate().profiles(workspaceId);
    }

    @Override
    public AutomationExecutionSnapshot freeze(
            WorkspaceId workspaceId, AgentProfileRef profile, CancellationToken cancellation) {
        return requireDelegate().freeze(workspaceId, profile, cancellation);
    }

    private AutomationExecutionPolicyPort requireDelegate() {
        AutomationExecutionPolicyPort current = delegate.get();
        if (current == null) {
            throw new IllegalStateException("Automation execution policy port is not bound");
        }
        return current;
    }
}
