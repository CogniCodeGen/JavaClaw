package com.javaclaw.server.extension;

import java.util.function.Supplier;

import com.javaclaw.browser.client.BrowserActionResult;
import com.javaclaw.browser.client.BrowserWorkerException;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;

/** 仅包围 Worker 调用；前置授权、附件校验与本地账本失败不会被误记为已派发动作。 */
final class BrowserOperationDispatch {
    private final BrowserPendingOrigins origins;
    private final BrowserSessionAuthority authority;
    private final BrowserSessionNetwork network;

    BrowserOperationDispatch(
            BrowserPendingOrigins origins, BrowserSessionAuthority authority, BrowserSessionNetwork network) {
        this.origins = origins;
        this.authority = authority;
        this.network = network;
    }

    BrowserActionResult open(
            IsolatedServiceInvocation invocation,
            BrowserSessionState session,
            BrowserSessionState.Access access,
            BrowserWorkerPort worker,
            BrowserContracts.OpenTask task,
            byte[] state) {
        var cancellation = authority.cancellation(session, access, invocation.cancellation());
        var callback = network.callback(session);
        return call(invocation, session, access, () -> worker.openInteractive(task, state, callback, cancellation));
    }

    BrowserActionResult act(
            IsolatedServiceInvocation invocation,
            BrowserSessionState session,
            BrowserSessionState.Access access,
            BrowserWorkerPort worker,
            BrowserUploadInput input) {
        var cancellation = authority.cancellation(session, access, invocation.cancellation());
        var action = input.action();
        var bytes = input.bytes();
        return call(
                invocation,
                session,
                access,
                () -> worker.actInteractive(session.id, access.lease(), action, bytes, cancellation));
    }

    private BrowserActionResult call(
            IsolatedServiceInvocation invocation,
            BrowserSessionState session,
            BrowserSessionState.Access access,
            Supplier<BrowserActionResult> action) {
        try {
            return action.get();
        } catch (BrowserWorkerException failure) {
            if (invocation.scope().turnId().isEmpty()
                    || !dispatchedFailure(failure)
                    || !origins.hasCurrent(session, access)) {
                throw failure;
            }
            origins.requestAfter(invocation, session, access);
            throw new BrowserOperationUnconfirmedException();
        }
    }

    private static boolean dispatchedFailure(BrowserWorkerException failure) {
        String message = failure.getMessage();
        return message != null && message.startsWith("BROWSER_");
    }

    static void preserveFailure(Exception original, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException failure) {
            if (!(original instanceof BrowserOperationUnconfirmedException)) {
                original.addSuppressed(failure);
            }
        }
    }
}
