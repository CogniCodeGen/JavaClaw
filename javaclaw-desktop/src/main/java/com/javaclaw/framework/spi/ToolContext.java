package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunRequest;

import java.time.Instant;

/** Per-run tool factory context; never cache it in extension singletons. */
public record ToolContext(
        RunId runId,
        RunScope scope,
        PermissionSet permissions,
        CancellationToken cancellation,
        Instant deadline,
        RunRequest request) {}
