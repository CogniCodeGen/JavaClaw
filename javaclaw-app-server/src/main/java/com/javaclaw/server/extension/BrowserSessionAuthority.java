package com.javaclaw.server.extension;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionResolver;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.server.security.grant.BrowserGrantService;

/** 浏览器执行授权与持有页面分离；新 Turn 重新冻结，撤销和人工接管立即切断旧租约。 */
final class BrowserSessionAuthority {
    private final SiteBrowserHostContext host;
    private final BrowserGrantService grants;

    BrowserSessionAuthority(SiteBrowserHostContext host, BrowserGrantService grants) {
        this.host = host;
        this.grants = grants;
    }

    ThreadId owner(IsolatedServiceInvocation invocation) {
        if (!BuiltinExtensionIds.SITE.equals(invocation.caller().value())) {
            throw new SecurityException("常驻浏览器仅允许内置 Site 扩展调用");
        }
        ThreadId thread = invocation.scope().threadId().orElseThrow(() -> new SecurityException("浏览器操作需要 Thread 身份"));
        if (!host.core().workspaceForThread(thread).id().equals(invocation.workspaceId())) {
            throw new SecurityException("浏览器 Thread 不属于调用 Workspace");
        }
        invocation.scope().turnId().ifPresent(turn -> requireTurn(thread, turn));
        return thread;
    }

    BrowserSessionState.Access acquire(IsolatedServiceInvocation invocation, long generation, boolean human) {
        ThreadId thread = owner(invocation);
        Set<URI> origins;
        if (human) {
            requireUser(invocation);
            origins = grants.currentOrigins(invocation.workspaceId(), thread);
        } else {
            TurnId turn = invocation.scope().turnId().orElseThrow(() -> new SecurityException("助手浏览器操作必须绑定活动 Turn"));
            origins = grants.freeze(invocation.workspaceId(), thread, turn).origins();
        }
        var lease = new BrowserContracts.AccessLease(
                human ? BrowserContracts.ControlMode.HUMAN : BrowserContracts.ControlMode.ASSISTANT,
                UUID.randomUUID().toString(),
                generation,
                host.clock().instant().plus(Duration.ofMinutes(15)),
                origins);
        return new BrowserSessionState.Access(
                lease, invocation.effectivePermissions(), invocation.scope().turnId(), new CancellationSource());
    }

    PermissionProfile authorize(BrowserSessionState session, URI uri) {
        return authorize(session, session.access, uri);
    }

    PermissionProfile authorize(BrowserSessionState session, BrowserSessionState.Access access, URI uri) {
        requireCurrent(session, access);
        URI origin = SiteContracts.originOf(uri);
        PermissionProfile permission = access.permission();
        if (access.lease().mode() == BrowserContracts.ControlMode.ASSISTANT) {
            AgentTurn turn = requireTurn(session.owner.threadId(), access.turn().orElseThrow());
            permission = currentPermissions(permission, turn);
            grants.requireAuthorized(
                    grants.freeze(session.owner.workspaceId(), session.owner.threadId(), turn.id()), origin);
        } else {
            grants.requireHumanAuthorized(session.owner.workspaceId(), session.owner.threadId(), origin);
        }
        requireCurrent(session, access);
        if (!access.lease().allowedOrigins().contains(origin)) {
            throw new SecurityException("浏览器来源不在当前控制代次中");
        }
        // 这是浏览器专用 Broker 投影，不写回普通 Profile，也不能被 MCP 或其他工具取用。
        return new PermissionProfile(
                "browser-lease",
                permission.version(),
                permission.files(),
                new NetworkPermission(
                        Set.of(origin.getHost()), Set.of(origin.getPort() < 0 ? 443 : origin.getPort()), true),
                permission.processes(),
                permission.tools(),
                permission.resources());
    }

    BrowserSessionState.Access release(BrowserSessionState session) {
        synchronized (session) {
            var previous = session.access;
            previous.cancelled().cancel("浏览器操作租约已释放");
            var lease = new BrowserContracts.AccessLease(
                    BrowserContracts.ControlMode.NONE,
                    UUID.randomUUID().toString(),
                    Math.addExact(previous.lease().generation(), 1),
                    host.clock().instant(),
                    Set.of());
            session.access = new BrowserSessionState.Access(
                    lease, previous.permission(), Optional.empty(), new CancellationSource());
            return session.access;
        }
    }

    BrowserSessionState.Access requireAssistant(BrowserSessionState session, IsolatedServiceInvocation invocation) {
        var access = session.access;
        requireCurrent(session, access);
        if (access.lease().mode() != BrowserContracts.ControlMode.ASSISTANT
                || !access.turn().equals(invocation.scope().turnId())
                || access.turn().isEmpty()) {
            throw new SecurityException("助手操作不能借用另一 Turn 或人工操作租约");
        }
        PermissionProfile current = currentPermissions(
                access.permission(),
                requireTurn(session.owner.threadId(), access.turn().orElseThrow()));
        String operation = host.json()
                .decode(invocation.request(), BrowserCommands.Invocation.class)
                .operation();
        if (!BrowserCommands.TOOL_NAMES.contains(operation)
                || !current.tools().allowedTools().contains(operation)) {
            throw new SecurityException("当前浏览器动作的精确工具权限已撤销");
        }
        requireCurrent(session, access);
        return access;
    }

    void requireCurrent(BrowserSessionState session, BrowserSessionState.Access access) {
        access.cancelled().throwIfCancelled();
        if (session.closed
                || session.closing
                || access != session.access
                || !access.lease().active(host.clock().instant())) {
            throw new SecurityException("浏览器没有与请求一致的有效操作租约");
        }
    }

    CancellationToken cancellation(
            BrowserSessionState session, BrowserSessionState.Access access, CancellationToken caller) {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return caller.isCancelled()
                        || access.cancelled().isCancelled()
                        || session.closed
                        || session.closing
                        || session.access != access
                        || !access.lease().active(host.clock().instant());
            }

            @Override
            public Optional<String> reason() {
                return isCancelled() ? Optional.of("浏览器请求已取消或控制代次已改变") : Optional.empty();
            }
        };
    }

    static void requireUser(IsolatedServiceInvocation invocation) {
        if (invocation.scope().turnId().isPresent()
                || invocation.scope().idempotencyKey().isEmpty()) {
            throw new SecurityException("该浏览器管理操作只能由用户显式提交");
        }
    }

    private AgentTurn requireTurn(ThreadId thread, TurnId turnId) {
        AgentTurn turn = host.core().findTurn(turnId).orElseThrow(() -> new SecurityException("浏览器所属 Turn 不存在"));
        if (!turn.threadId().equals(thread)
                || !(turn.status() == TurnStatus.RUNNING || turn.status() == TurnStatus.WAITING)) {
            throw new SecurityException("浏览器所属 Turn 已结束或归属不同");
        }
        return turn;
    }

    private PermissionProfile currentPermissions(PermissionProfile frozen, AgentTurn turn) {
        var workspace = host.core().workspaceForThread(turn.threadId());
        var current = host.permissions()
                .resolveForExecution(
                        turn.permissionProfile().id(),
                        turn.permissionProfile().version(),
                        workspace,
                        turn.executionRoot(),
                        false);
        PermissionProfile result = PermissionResolver.intersect(List.of(frozen, current));
        if (BrowserCommands.TOOL_NAMES.stream().noneMatch(result.tools().allowedTools()::contains)) {
            throw new SecurityException("当前浏览器工具权限已撤销");
        }
        return result;
    }
}
