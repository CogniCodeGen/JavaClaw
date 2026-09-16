package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.extension.spi.IsolatedServiceCallScope;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.server.persistence.CommandIdentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserLeaseReceiptTest {
    @TempDir
    Path directory;

    @Test
    void 不一致控制回执和Worker控制失败都必须关闭会话() throws Exception {
        for (String failure : List.of("owner", "lease", "worker")) {
            try (var fixture = new InteractiveBrowserHostFixture(directory.resolve(failure))) {
                fixture.service.invoke(fixture.modelInvocation(fixture.turn(), "browser_open", open()));
                fixture.wrongLeaseOwner = failure.equals("owner");
                fixture.wrongLeaseValue = failure.equals("lease");
                fixture.failLease = failure.equals("worker");
                assertThrows(
                        RuntimeException.class,
                        () -> fixture.service.invoke(fixture.invocation(
                                "browser.takeover",
                                Map.of(),
                                fixture.view.lease().generation())));
                assertEquals(1, fixture.closes);
                assertTrue(status(fixture).session().isEmpty());
            }
        }
    }

    @Test
    void 旧控制回执晚于用户交还时不得回退代次() throws Exception {
        lateReceipt(false, false);
    }

    @Test
    void 旧控制失败晚于用户交还时不得关闭新会话() throws Exception {
        lateReceipt(true, false);
    }

    @Test
    void 旧控制回执晚于窗口关闭时不得恢复会话() throws Exception {
        lateReceipt(false, true);
    }

    @Test
    void 关闭多个会话即使单个回收失败仍完成其余清理并保留错误() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var request = fixture.invocation("browser.open", open(), 0);
            fixture.service.invoke(request);
            var other = fixture.host
                    .core()
                    .createThread(
                            identity(), fixture.workspace, Optional.empty(), ThreadExecutionIntent.WORKSPACE, "other")
                    .id();
            fixture.grants.confirm(
                    identity(), fixture.grants.preview(fixture.workspace, other, InteractiveBrowserHostFixture.ORIGIN));
            var scope = new IsolatedServiceCallScope(
                    Optional.of(other),
                    Optional.empty(),
                    Optional.of(UUID.randomUUID().toString()),
                    0);
            fixture.service.invoke(new IsolatedServiceInvocation(
                    request.caller(),
                    request.workspaceId(),
                    request.effectivePermissions(),
                    request.serviceId(),
                    request.request(),
                    request.cancellation(),
                    scope));
            fixture.failClose = true;
            var failure = assertThrows(IllegalStateException.class, fixture.service::close);
            assertEquals(2, fixture.closes);
            assertEquals(1, failure.getCause().getSuppressed().length);
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.service.invoke(fixture.invocation("browser.status", Map.of(), 0)));
        }
    }

    private void lateReceipt(boolean failed, boolean close) throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            fixture.service.invoke(fixture.modelInvocation(fixture.turn(), "browser_open", open()));
            fixture.blockLease = true;
            var request = fixture.invocation(
                    "browser.takeover", Map.of(), fixture.view.lease().generation());
            var pending = CompletableFuture.runAsync(() -> {
                try {
                    fixture.service.invoke(request);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            try {
                assertTrue(fixture.leaseStarted.await(3, TimeUnit.SECONDS));
                fixture.service.invoke(fixture.invocation(
                        close ? "browser.close" : "browser.return",
                        Map.of(),
                        fixture.view.lease().generation()));
                long expected = fixture.view.lease().generation();
                fixture.failLease = failed;
                fixture.leaseGate.complete(null);
                pending.get(5, TimeUnit.SECONDS);
                if (close) {
                    assertTrue(status(fixture).session().isEmpty());
                    assertEquals(1, fixture.closes);
                } else {
                    var session = status(fixture).session().orElseThrow();
                    assertEquals(
                            BrowserContracts.ControlMode.NONE, session.lease().mode());
                    assertEquals(expected, session.lease().generation());
                    assertEquals(0, fixture.closes);
                }
            } finally {
                fixture.leaseGate.complete(null);
            }
        }
    }

    private static BrowserCommands.Status status(InteractiveBrowserHostFixture fixture) throws Exception {
        return fixture.json.decode(
                fixture.service.invoke(fixture.invocation("browser.status", Map.of(), 0)),
                BrowserCommands.Status.class);
    }

    private static BrowserCommands.Open open() {
        return new BrowserCommands.Open(InteractiveBrowserHostFixture.ORIGIN, Optional.empty(), false);
    }

    private static CommandIdentity identity() {
        return new CommandIdentity("browser-test/create", UUID.randomUUID().toString(), 0, "a".repeat(64));
    }
}
