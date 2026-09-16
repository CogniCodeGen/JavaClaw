package com.javaclaw.server.extension;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.javaclaw.api.InputRequest;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.security.grant.BrowserGrantService;

/** 同一 Turn 和 Origin 固定一份预览及输入请求；崩溃恢复不能换掉用户确认的摘要或延长有效期。 */
final class BrowserOriginConfirmations {
    private final SiteBrowserHostContext host;
    private final BrowserGrantService grants;
    private final H2ManagedExtensionStore store;

    BrowserOriginConfirmations(SiteBrowserHostContext host, BrowserGrantService grants) {
        this.host = host;
        this.grants = grants;
        store = new H2ManagedExtensionStore(host.database(), host.clock());
    }

    Confirmation get(IsolatedServiceInvocation invocation, URI origin) throws Exception {
        var turn = invocation.scope().turnId().orElseThrow();
        String id = "browser-origin-"
                + host.json().encode(Map.of("turn", turn, "origin", origin)).sha256();
        return store.inTransaction(new ExtensionId(BuiltinExtensionIds.SITE), tx -> {
            var prior = tx.get("browser.origin-prompts", id);
            if (prior.isPresent()) {
                Confirmation stored = host.json().decode(prior.orElseThrow().payload(), Confirmation.class);
                requireOwner(invocation, origin, stored);
                return stored;
            }
            var preview = grants.preview(
                    invocation.workspaceId(), invocation.scope().threadId().orElseThrow(), origin);
            var schema = host.json()
                    .encode(Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("allow", Map.of("type", "boolean", "title", "允许此浏览器访问")),
                            "required",
                            List.of("allow"),
                            "additionalProperties",
                            false));
            var request = new InputRequest(
                    id,
                    turn,
                    BuiltinExtensionIds.SITE,
                    "允许当前对话的浏览器访问 " + origin + " 吗？此授权不授予其他工具。",
                    schema,
                    host.clock().instant(),
                    preview.expiresAt());
            Confirmation created = new Confirmation(preview, request);
            tx.put("browser.origin-prompts", id, 0, host.json().encode(created));
            return created;
        });
    }

    private static void requireOwner(IsolatedServiceInvocation invocation, URI origin, Confirmation confirmation) {
        var preview = confirmation.preview();
        if (!preview.workspaceId().equals(invocation.workspaceId())
                || !preview.threadId().equals(invocation.scope().threadId().orElseThrow())
                || !preview.origin().equals(origin)
                || !confirmation
                        .request()
                        .turnId()
                        .equals(invocation.scope().turnId().orElseThrow())) {
            throw new SecurityException("浏览器来源确认不属于当前调用上下文");
        }
    }

    record Confirmation(BrowserGrantContracts.Preview preview, InputRequest request) {}
}
