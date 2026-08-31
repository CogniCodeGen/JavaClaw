package com.javaclaw.server.collaboration;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.agent.collaboration.CollaborationGateway;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;

/** Breaks the intentional Kernel -> Tool -> Collaboration -> AgentRuntime assembly cycle. */
public final class DeferredCollaborationGateway implements CollaborationGateway {
    private final AtomicReference<CollaborationGateway> target = new AtomicReference<>();

    /** 在组件装配完成后绑定唯一协作实现；重复绑定拒绝，避免运行中替换权限边界。 */
    public void bind(CollaborationGateway value) {
        if (!target.compareAndSet(null, Objects.requireNonNull(value, "value"))) {
            throw new IllegalStateException("collaboration gateway is already bound");
        }
    }

    private CollaborationGateway target() {
        CollaborationGateway value = target.get();
        if (value == null) {
            throw new IllegalStateException("collaboration runtime is not ready");
        }
        return value;
    }

    @Override
    public AgentThread spawn(SpawnRequest request) {
        return target().spawn(request);
    }

    @Override
    public boolean steer(TurnId turnId, TurnInput input) {
        return target().steer(turnId, input);
    }

    @Override
    public Optional<ThreadSnapshot> read(ThreadId childThreadId) {
        return target().read(childThreadId);
    }

    @Override
    public Optional<ThreadSnapshot> waitForTerminal(ThreadId childThreadId, Duration timeout) {
        return target().waitForTerminal(childThreadId, timeout);
    }

    @Override
    public boolean cancel(ThreadId childThreadId) {
        return target().cancel(childThreadId);
    }

    @Override
    public List<AgentThread> children(ThreadId parentThreadId) {
        return target().children(parentThreadId);
    }

    @Override
    public PatchResult diff(ThreadId childThreadId) {
        return target().diff(childThreadId);
    }

    @Override
    public PatchResult apply(ThreadId parentThreadId, ThreadId childThreadId, String idempotencyKey) {
        return target().apply(parentThreadId, childThreadId, idempotencyKey);
    }

    @Override
    public boolean cleanup(ThreadId childThreadId, boolean discardUnmerged) {
        return target().cleanup(childThreadId, discardUnmerged);
    }
}
