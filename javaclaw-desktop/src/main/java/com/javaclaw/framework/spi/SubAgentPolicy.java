package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunRequest;

/** Additional ceiling applied only when a request has a parentRunId. */
@FunctionalInterface
public interface SubAgentPolicy {
    RunConstraints restrict(RunRequest childRequest, RunConstraints current);
}
