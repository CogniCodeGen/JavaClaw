package com.javaclaw.server.extension;

import java.net.URI;
import java.util.Map;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.security.grant.BrowserGrantService;

/** 浏览器授权的扩展命令入口；身份只来自平台 Scope，不从模型参数推断用户确认。 */
final class BrowserGrantCommands {
    private final SiteBrowserHostContext host;
    private final BrowserGrantService grants;

    BrowserGrantCommands(SiteBrowserHostContext host, BrowserGrantService grants) {
        this.host = host;
        this.grants = grants;
    }

    CanonicalPayload invoke(IsolatedServiceInvocation invocation, BrowserCommands.Invocation command) {
        ThreadId thread = owner(invocation);
        return switch (command.operation()) {
            case "browser.grants" ->
                host.json()
                        .encode(new BrowserGrantContracts.GrantList(
                                grants.listLatest(invocation.workspaceId(), thread)));
            case "browser.grant.preview" ->
                host.json()
                        .encode(grants.preview(
                                invocation.workspaceId(),
                                thread,
                                URI.create(host.json()
                                        .textField(command.payload(), "origin")
                                        .orElseThrow())));
            case "browser.grant.confirm" -> confirm(invocation, command, thread);
            case "browser.grant.revoke" -> revoke(invocation, command, thread);
            default -> throw new IllegalArgumentException("不支持的浏览器授权命令");
        };
    }

    private CanonicalPayload confirm(
            IsolatedServiceInvocation invocation, BrowserCommands.Invocation command, ThreadId thread) {
        BrowserSessionAuthority.requireUser(invocation);
        var preview = host.json().decode(command.payload(), BrowserGrantContracts.Preview.class);
        if (!preview.workspaceId().equals(invocation.workspaceId())
                || !preview.threadId().equals(thread)) {
            throw new SecurityException("浏览器授权确认不能跨 Workspace 或 Thread");
        }
        return host.json().encode(grants.confirm(identity(invocation, command), preview));
    }

    private CanonicalPayload revoke(
            IsolatedServiceInvocation invocation, BrowserCommands.Invocation command, ThreadId thread) {
        BrowserSessionAuthority.requireUser(invocation);
        String id = host.json().textField(command.payload(), "id").orElseThrow();
        boolean owned = grants.listLatest(invocation.workspaceId(), thread).stream()
                .anyMatch(grant -> grant.id().equals(id));
        if (!owned) {
            throw new SecurityException("浏览器授权撤销不能跨 Workspace 或 Thread");
        }
        return host.json().encode(grants.revoke(identity(invocation, command), id));
    }

    private ThreadId owner(IsolatedServiceInvocation invocation) {
        if (!BuiltinExtensionIds.SITE.equals(invocation.caller().value())
                || !BrowserCommands.SERVICE.equals(invocation.serviceId())) {
            throw new SecurityException("浏览器授权只对 Site 浏览器服务开放");
        }
        invocation.cancellation().throwIfCancelled();
        ThreadId thread = invocation.scope().threadId().orElseThrow(() -> new SecurityException("浏览器授权需要 Thread 身份"));
        if (!host.core().workspaceForThread(thread).id().equals(invocation.workspaceId())) {
            throw new SecurityException("浏览器授权 Thread 不属于调用 Workspace");
        }
        return thread;
    }

    private CommandIdentity identity(IsolatedServiceInvocation invocation, BrowserCommands.Invocation command) {
        var payload = host.json()
                .encode(Map.of(
                        "workspaceId", invocation.workspaceId(), "scope", invocation.scope(), "command", command));
        var write = new WriteCommand(
                invocation.scope().idempotencyKey().orElseThrow(),
                invocation.scope().expectedRevision(),
                payload);
        return CommandIdentity.from("site/" + command.operation(), write, host.json());
    }
}
