package com.javaclaw.server.extension.thirdparty;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;

/** 单次第三方 Worker 调用的完整、不可变语义参数。 */
record ThirdPartyInvocation(
        ThirdPartyWorkerClient.InvocationKind kind,
        String operation,
        Workspace workspace,
        Optional<ThreadId> threadId,
        Optional<TurnId> turnId,
        CanonicalPayload payload,
        Optional<String> idempotencyKey,
        long expectedRevision,
        Optional<PermissionProfile> caller,
        CancellationToken cancellation) {
    ThirdPartyInvocation {
        Objects.requireNonNull(kind, "kind");
        operation = Objects.requireNonNull(operation, "operation").strip();
        if (operation.isEmpty() || expectedRevision < 0) {
            throw new IllegalArgumentException("operation and expectedRevision are invalid");
        }
        Objects.requireNonNull(workspace, "workspace");
        threadId = Objects.requireNonNull(threadId, "threadId");
        turnId = Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(payload, "payload");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        caller = Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(cancellation, "cancellation");
    }
}
