package com.javaclaw.server.turn;

import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.server.persistence.CoreCommandService;

/** 从持久父子关系传播取消；重启不丢失父 Turn 的取消、失败或超时。 */
final class ParentTurnCancellation implements CancellationToken {
    private final CoreCommandService core;
    private final TurnId parent;
    private final CancellationToken local;
    private final java.time.Clock clock;

    ParentTurnCancellation(CoreCommandService core, TurnId parent, CancellationToken local, java.time.Clock clock) {
        this.core = core;
        this.parent = parent;
        this.local = local;
        this.clock = clock;
    }

    @Override
    public boolean isCancelled() {
        if (local.isCancelled()) {
            return true;
        }
        var current = core.findTurn(parent);
        if (current.isEmpty() || core.cancellationRequested(parent)) {
            return true;
        }
        var turn = current.orElseThrow();
        if (!clock.instant().isBefore(turn.createdAt().plus(turn.budget().wallTime()))) {
            return true;
        }
        if (turn.status() == TurnStatus.CANCELLED || turn.status() == TurnStatus.FAILED) {
            return true;
        }
        return core.parentTurn(turn.threadId())
                .map(value -> new ParentTurnCancellation(core, value.id(), local, clock).isCancelled())
                .orElse(false);
    }

    @Override
    public Optional<String> reason() {
        return isCancelled() ? Optional.of("父任务取消或失败") : Optional.empty();
    }
}
