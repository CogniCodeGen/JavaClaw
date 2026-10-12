package com.javaclaw.framework.api;

import java.util.List;
import java.util.Optional;

/** Durable atomic operations belonging to a turn. */
public interface StepClient {
    List<AgentStep> steps(RunId turnId);
    Optional<AgentStep> step(RunId turnId, StepId stepId);
}
