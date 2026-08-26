package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunId;

/** Resolves Run-scoped resources for host integrations and releases them at terminal cleanup. */
public interface RunResourceRegistry {
    RunResourceScope forRun(RunId runId);

    void release(RunId runId);
}
