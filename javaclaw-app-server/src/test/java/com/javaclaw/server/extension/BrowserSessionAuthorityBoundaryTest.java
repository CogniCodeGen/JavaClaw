package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceCallScope;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2TurnJournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserSessionAuthorityBoundaryTest {
    @TempDir
    Path directory;

    @Test
    void 所有权必须来自Site且携带真实Workspace和Thread以及活动Turn() {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var authority = new BrowserSessionAuthority(fixture.host, fixture.grants);
            var user = fixture.invocation("browser.status", Map.of(), 0);
            assertEquals(fixture.thread, authority.owner(user));
            assertThrows(
                    SecurityException.class,
                    () -> authority.owner(
                            replace(user, new ExtensionId("third.party"), user.workspaceId(), user.scope())));
            assertThrows(
                    SecurityException.class,
                    () -> authority.owner(replace(user, user.caller(), WorkspaceId.random(), user.scope())));
            var noThread = new IsolatedServiceCallScope(
                    Optional.empty(), Optional.empty(), user.scope().idempotencyKey(), 0);
            assertThrows(
                    SecurityException.class,
                    () -> authority.owner(replace(user, user.caller(), user.workspaceId(), noThread)));
            assertThrows(SecurityException.class, () -> authority.owner(withTurn(user, TurnId.random())));
            var turn = fixture.turn();
            assertEquals(fixture.thread, authority.owner(withTurn(user, turn)));
            fixture.finish(turn);
            assertThrows(SecurityException.class, () -> authority.owner(withTurn(user, turn)));
        }
    }

    @Test
    void 等待中的Turn保留授权但其他Thread不能借用它() {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            var request = fixture.modelInvocation(turn, "browser_act", Map.of());
            var authority = new BrowserSessionAuthority(fixture.host, fixture.grants);
            ThreadId other = fixture.host
                    .core()
                    .createThread(
                            identity(),
                            fixture.workspace,
                            Optional.empty(),
                            com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                            "other")
                    .id();
            var scope = new IsolatedServiceCallScope(
                    Optional.of(other), Optional.of(turn), request.scope().idempotencyKey(), 0);
            assertThrows(
                    SecurityException.class,
                    () -> authority.owner(replace(request, request.caller(), request.workspaceId(), scope)));
            new H2TurnJournal(
                            fixture.host.database(),
                            CoreItemCodecs.createRegistry(fixture.json),
                            fixture.json,
                            fixture.host.clock())
                    .transition(turn, TurnStatus.RUNNING, TurnStatus.WAITING, Optional.empty());
            assertEquals(fixture.thread, authority.owner(request));
        }
    }

    @Test
    void 人工授权仅投影精确Https来源和端口而不扩大原Profile() {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var authority = new BrowserSessionAuthority(fixture.host, fixture.grants);
            var user = fixture.invocation("browser.takeover", Map.of(), 0);
            URI custom = URI.create("https://docs.example.com:8443");
            fixture.grants.confirm(identity(), fixture.grants.preview(fixture.workspace, fixture.thread, custom));
            var access = authority.acquire(user, 1, true);
            var session = session(fixture, access);
            var tls = authority.authorize(session, InteractiveBrowserHostFixture.ORIGIN.resolve("/private?x=1"));
            assertEquals(Set.of("docs.example.com"), tls.network().hosts());
            assertEquals(Set.of(443), tls.network().ports());
            assertTrue(tls.network().tlsOnly());
            assertEquals(
                    Set.of(8443), authority.authorize(session, custom).network().ports());
            assertEquals(user.effectivePermissions(), access.permission());
            assertThrows(
                    SecurityException.class,
                    () -> authority.authorize(session, URI.create("https://unapproved.example.com")));
            var narrowed = new BrowserContracts.AccessLease(
                    access.lease().mode(),
                    access.lease().leaseId(),
                    access.lease().generation(),
                    access.lease().expiresAt(),
                    Set.of(custom));
            session.access = new BrowserSessionState.Access(
                    narrowed, access.permission(), access.turn(), new CancellationSource());
            assertThrows(
                    SecurityException.class, () -> authority.authorize(session, InteractiveBrowserHostFixture.ORIGIN));
        }
    }

    @Test
    void 助手授权重新检查活动Turn及精确工具权限() {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            var invocation = fixture.modelInvocation(turn, "browser_act", Map.of());
            var authority = new BrowserSessionAuthority(fixture.host, fixture.grants);
            var access = authority.acquire(invocation, 1, false);
            var session = session(fixture, access);
            assertEquals(access, authority.requireAssistant(session, invocation));
            assertEquals(
                    Set.of(443),
                    authority
                            .authorize(session, InteractiveBrowserHostFixture.ORIGIN)
                            .network()
                            .ports());
            assertThrows(
                    SecurityException.class,
                    () -> authority.requireAssistant(session, withTurn(invocation, TurnId.random())));
            session.access = restricted(access, Set.of("browser_open"));
            assertThrows(SecurityException.class, () -> authority.requireAssistant(session, invocation));
            session.access = restricted(access, Set.of());
            assertThrows(SecurityException.class, () -> authority.requireAssistant(session, invocation));
            session.access = access;
            assertThrows(
                    SecurityException.class,
                    () -> authority.requireAssistant(
                            session, fixture.modelInvocation(turn, "browser.status", Map.of())));
        }
    }

    @Test
    void 无Turn或人工租约不能被助手借用且管理操作必须有显式用户身份() {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var authority = new BrowserSessionAuthority(fixture.host, fixture.grants);
            var user = fixture.invocation("browser.takeover", Map.of(), 0);
            assertThrows(SecurityException.class, () -> authority.acquire(user, 1, false));
            var turn = fixture.turn();
            var model = fixture.modelInvocation(turn, "browser_act", Map.of());
            assertThrows(SecurityException.class, () -> authority.acquire(model, 1, true));
            var human = authority.acquire(user, 1, true);
            assertThrows(SecurityException.class, () -> authority.requireAssistant(session(fixture, human), model));
            var assistant = authority.acquire(model, 1, false);
            var missing = new BrowserSessionState.Access(
                    assistant.lease(), assistant.permission(), Optional.empty(), new CancellationSource());
            assertThrows(SecurityException.class, () -> authority.requireAssistant(session(fixture, missing), user));
            var anonymous =
                    new IsolatedServiceCallScope(Optional.of(fixture.thread), Optional.empty(), Optional.empty(), 0);
            assertThrows(
                    SecurityException.class,
                    () -> BrowserSessionAuthority.requireUser(
                            replace(user, user.caller(), user.workspaceId(), anonymous)));
        }
    }

    @Test
    void 关闭撤权替换和过期均撤销冻结请求并给出统一取消原因() {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var authority = new BrowserSessionAuthority(fixture.host, fixture.grants);
            var user = fixture.invocation("browser.takeover", Map.of(), 0);
            for (String transition : List.of("closed", "closing", "replacement", "expired", "cancelled")) {
                var access = authority.acquire(user, 1, true);
                var session = session(fixture, access);
                var token = authority.cancellation(session, access, new CancellationSource());
                assertFalse(token.isCancelled());
                assertTrue(token.reason().isEmpty());
                invalidate(fixture, session, transition);
                assertTrue(token.isCancelled(), transition);
                assertTrue(token.reason().isPresent(), transition);
                assertThrows(RuntimeException.class, () -> authority.requireCurrent(session, access), transition);
                assertThrows(
                        SecurityException.class,
                        () -> BrowserSessionActivity.touch(session, access, fixture.host.clock()),
                        transition);
            }
            var access = authority.acquire(user, 1, true);
            var caller = new CancellationSource();
            var token = authority.cancellation(session(fixture, access), access, caller);
            caller.cancel("user cancelled");
            assertTrue(token.isCancelled());
            assertTrue(token.reason().isPresent());
        }
    }

    private static void invalidate(
            InteractiveBrowserHostFixture fixture, BrowserSessionState session, String transition) {
        switch (transition) {
            case "closed" -> session.closed = true;
            case "closing" -> session.closing = true;
            case "replacement" ->
                session.access = new BrowserSessionState.Access(
                        session.access.lease(),
                        session.access.permission(),
                        session.access.turn(),
                        new CancellationSource());
            case "expired" -> fixture.advance(Duration.ofMinutes(16));
            case "cancelled" -> session.access.cancelled().cancel("authority revoked");
            default -> throw new IllegalArgumentException(transition);
        }
    }

    private static BrowserSessionState.Access restricted(BrowserSessionState.Access access, Set<String> tools) {
        var original = access.permission();
        var permissions = new PermissionProfile(
                original.id(),
                original.version(),
                original.files(),
                original.network(),
                original.processes(),
                new ToolPermission(
                        tools, original.tools().maximumRisk(), original.tools().approvalRequirement()),
                original.resources());
        return new BrowserSessionState.Access(access.lease(), permissions, access.turn(), new CancellationSource());
    }

    private static BrowserSessionState session(
            InteractiveBrowserHostFixture fixture, BrowserSessionState.Access access) {
        return new BrowserSessionState(
                UUID.randomUUID().toString(),
                new BrowserContracts.Owner(fixture.workspace, fixture.thread, Optional.empty()),
                Optional.empty(),
                access,
                false,
                fixture.host.clock().instant());
    }

    private static IsolatedServiceInvocation withTurn(IsolatedServiceInvocation original, TurnId turn) {
        var scope = new IsolatedServiceCallScope(
                original.scope().threadId(), Optional.of(turn), original.scope().idempotencyKey(), 0);
        return replace(original, original.caller(), original.workspaceId(), scope);
    }

    private static IsolatedServiceInvocation replace(
            IsolatedServiceInvocation original,
            ExtensionId caller,
            WorkspaceId workspace,
            IsolatedServiceCallScope scope) {
        return new IsolatedServiceInvocation(
                caller,
                workspace,
                original.effectivePermissions(),
                original.serviceId(),
                original.request(),
                original.cancellation(),
                scope);
    }

    private static CommandIdentity identity() {
        return new CommandIdentity("browser/confirm", UUID.randomUUID().toString(), 0, "0".repeat(64));
    }
}
