package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunRequest;

import java.util.List;

/**
 * Read-only context source. Search returns metadata only; fetch returns one selected body.
 * Implementations must reject a stale version rather than silently returning changed content.
 * The runtime executes both operations through ToolInvocationGateway for authorization and audit.
 */
public interface DeferredContextSource {
    String id();

    String description();

    default String group() { return "context"; }

    PermissionSet requiredPermissions();

    List<DeferredContextCandidate> search(RunRequest request, String query, int limit);

    String fetch(RunRequest request, String candidateId, String version);
}
