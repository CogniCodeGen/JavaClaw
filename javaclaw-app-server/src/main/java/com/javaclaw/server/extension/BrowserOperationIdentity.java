package com.javaclaw.server.extension;

import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.protocol.CanonicalJson;

/** 幂等摘要冻结真实操作与可信身份；临时会话 ID 不参与摘要，避免启动重试改变身份。 */
final class BrowserOperationIdentity {
    private BrowserOperationIdentity() {}

    static CanonicalPayload fingerprint(
            CanonicalJson json,
            IsolatedServiceInvocation invocation,
            BrowserSessionState session,
            CanonicalPayload payload,
            Optional<BrowserContracts.Action> action) {
        String operation = json.decode(invocation.request(), BrowserCommands.Invocation.class)
                .operation();
        return json.encode(Map.of(
                "operation",
                operation,
                "workspace",
                invocation.workspaceId(),
                "thread",
                session.owner.threadId(),
                "turn",
                invocation.scope().turnId(),
                "payload",
                payload,
                "action",
                action,
                "account",
                session.owner.account()));
    }

    static IsolatedServiceInvocation withCancellation(
            IsolatedServiceInvocation invocation, CancellationToken cancellation) {
        return new IsolatedServiceInvocation(
                invocation.caller(),
                invocation.workspaceId(),
                invocation.effectivePermissions(),
                invocation.serviceId(),
                invocation.request(),
                cancellation,
                invocation.scope());
    }
}
