package com.javaclaw.server.extension;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.security.grant.BrowserGrantService;

/** 新来源沿用聊天结构化输入确认；批准只供后续 Turn 冻结，绝不修改当前执行快照。 */
final class BrowserOriginRequests {
    static final String CONTINUATION = "BROWSER_AUTHORIZATION_CONTINUATION";
    private final SiteBrowserHostContext host;
    private final BrowserGrantService grants;
    private final BiConsumer<TurnId, URI> continuation;

    BrowserOriginRequests(
            SiteBrowserHostContext host, BrowserGrantService grants, BiConsumer<TurnId, URI> continuation) {
        this.host = host;
        this.grants = grants;
        this.continuation = continuation;
    }

    Optional<CanonicalPayload> require(IsolatedServiceInvocation invocation, URI origin) throws Exception {
        var thread = invocation.scope().threadId().orElseThrow();
        if (invocation.scope().turnId().isEmpty()) {
            grants.requireHumanAuthorized(invocation.workspaceId(), thread, origin);
            return Optional.empty();
        }
        TurnId turn = invocation.scope().turnId().orElseThrow();
        var snapshot = grants.freeze(invocation.workspaceId(), thread, turn);
        if (snapshot.origins().contains(origin)) {
            grants.requireAuthorized(snapshot, origin);
            return Optional.empty();
        }
        if (!grants.currentOrigins(invocation.workspaceId(), thread).contains(origin)) {
            confirm(invocation, origin);
        }
        continuation.accept(turn, origin);
        return Optional.of(host.json()
                .encode(Map.of(
                        "status",
                        CONTINUATION,
                        "origin",
                        origin,
                        "detail",
                        "已确认浏览器专用来源授权，已请求新 Turn 继续；若预算或配置不允许，将报告未续接原因。")));
    }

    private void confirm(IsolatedServiceInvocation invocation, URI origin) throws Exception {
        var confirmation = new BrowserOriginConfirmations(host, grants).get(invocation, origin);
        var preview = confirmation.preview();
        var request = confirmation.request();
        var response = host.inputs()
                .await(request, invocation.cancellation())
                .orElseThrow(() -> new SecurityException("浏览器来源授权未确认"));
        if (!host.json().decode(response, Decision.class).allow()) {
            throw new SecurityException("用户拒绝了浏览器来源授权");
        }
        var identity = new CommandIdentity(
                "site/browser/grant/confirm",
                request.id() + ":confirm",
                0,
                host.json().encode(preview).sha256());
        grants.confirm(identity, preview);
    }

    private record Decision(boolean allow) {}
}
