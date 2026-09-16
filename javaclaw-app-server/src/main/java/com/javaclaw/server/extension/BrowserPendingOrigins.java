package com.javaclaw.server.extension;

import java.net.URI;
import java.util.Objects;

import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;

/** 子资源拒绝通知只生成用户确认；动作回执已先落账，批准不会重放点击、表单或上传。 */
final class BrowserPendingOrigins {
    private final BrowserOriginRequests requests;

    BrowserPendingOrigins(BrowserOriginRequests requests) {
        this.requests = requests;
    }

    void requestAfter(
            IsolatedServiceInvocation invocation, BrowserSessionState session, BrowserSessionState.Access access) {
        if (invocation.scope().turnId().isEmpty() || access.lease().mode() != BrowserContracts.ControlMode.ASSISTANT) {
            return;
        }
        URI selected = select(session, access);
        if (selected == null) {
            return;
        }
        try {
            requests.require(invocation, selected);
        } catch (TurnCancelledException | SecurityException declined) {
            // 用户拒绝、取消或旧租约失效不改变此前已完成动作的权威回执。
        } catch (Exception unavailable) {
            // 保留待授权来源供用户显式处理；不能把已发生操作变成可重试失败。
        }
    }

    boolean hasCurrent(BrowserSessionState session, BrowserSessionState.Access access) {
        synchronized (session) {
            return !session.closed
                    && !session.closing
                    && session.access == access
                    && !access.cancelled().isCancelled()
                    && session.pendingOrigins.containsValue(access.lease().generation());
        }
    }

    String detail(BrowserSessionState session, String ready) {
        synchronized (session) {
            session.pendingOrigins.keySet().removeAll(session.access.lease().allowedOrigins());
            if (session.pendingOrigins.isEmpty()) {
                return ready;
            }
            return ready + "；部分资源待授权："
                    + session.pendingOrigins.keySet().iterator().next();
        }
    }

    private URI select(BrowserSessionState session, BrowserSessionState.Access access) {
        synchronized (session) {
            if (session.closed
                    || session.closing
                    || session.access != access
                    || access.cancelled().isCancelled()) {
                return null;
            }
            long generation = access.lease().generation();
            session.promptedOrigins.entrySet().removeIf(entry -> entry.getValue() != generation);
            for (var candidate : session.pendingOrigins.entrySet()) {
                if (candidate.getValue() == generation
                        && !Objects.equals(session.promptedOrigins.get(candidate.getKey()), generation)) {
                    session.promptedOrigins.put(candidate.getKey(), generation);
                    return candidate.getKey();
                }
            }
            return null;
        }
    }
}
