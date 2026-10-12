package com.javaclaw.framework.api;

import java.time.Instant;

public record ThreadSnapshot(ThreadId id, RunScope scope, String title, ThreadStatus status,
                             ThreadConfiguration configuration, ThreadId parentThreadId,
                             TurnId parentTurnId, ThreadId forkSourceThreadId, long forkSequence,
                             long generation, long lastSequence, Instant createdAt, Instant updatedAt) { }
