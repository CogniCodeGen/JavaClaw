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
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteRegistrationServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 临时窗口不创建网站且完成原子写入默认账号密码登录态和回执() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            assertEquals(0, fixture.siteCount());
            assertEquals(0, fixture.vault.status().credentialCount());
            assertFalse(fixture.json.encode(active).json().contains("token=secret"));
            var request = fixture.confirmation(active, true);
            var completed = fixture.call("registration.complete", request, "save");
            assertEquals(State.COMPLETED, completed.state());
            assertEquals(completed, fixture.status(active.sessionId()));
            assertEquals(completed, fixture.call("registration.complete", request, "save"));
            assertEquals(1, fixture.worker.saves);
            assertEquals(1, fixture.siteCount());
            assertEquals(2, fixture.vault.status().credentialCount());
            var saved = completed.completed().orElseThrow();
            var accounts = fixture.accounts
                    .list(SiteRegistrationFixture.WORKSPACE, saved.siteId())
                    .accounts();
            assertEquals(1, accounts.size());
            assertTrue(accounts.getFirst().defaultAccount());
            assertTrue(accounts.getFirst().passwordConfigured());
            assertTrue(accounts.getFirst().loginStateConfigured());
            var scope = new SiteAccountContracts.AccountScope(
                    SiteRegistrationFixture.WORKSPACE, saved.siteId(), saved.accountId());
            assertArrayEquals(SiteRegistrationFixture.STORAGE, fixture.accounts.useState(scope, 1, byte[]::clone));
            assertArrayEquals(new byte[fixture.worker.exportedState.length], fixture.worker.exportedState);
            assertArrayEquals(new byte[fixture.worker.exportedCredentials.length], fixture.worker.exportedCredentials);
            assertFalse(fixture.json.encode(completed).json().contains("password"));
        }
    }

    @Test
    void 同Workspace独占取消不留空网站且同启动身份不会再次打开窗口() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            assertEquals(active, fixture.begin());
            assertEquals(1, fixture.worker.starts);
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.call(
                            "registration.begin",
                            new SiteRegistrationContracts.BeginRequest(SiteRegistrationFixture.ORIGIN),
                            "other"));
            var cancelled = fixture.call(
                    "registration.cancel", new SiteRegistrationContracts.SessionRequest(active.sessionId()), "cancel");
            assertEquals(State.CANCELLED, cancelled.state());
            assertEquals(cancelled, fixture.begin());
            assertEquals(1, fixture.worker.starts);
            assertEquals(0, fixture.siteCount());
            assertEquals(0, fixture.vault.status().credentialCount());
            assertEquals(
                    cancelled,
                    fixture.call(
                            "registration.cancel",
                            new SiteRegistrationContracts.SessionRequest(active.sessionId()),
                            "cancel2"));
        }
    }

    @Test
    void 来源必须显式追加且不延长租约并保存授权集合() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            URI origin = URI.create("https://cdn.example.com");
            fixture.worker.network.deniedOrigin(origin, 1);
            var denied = fixture.status(active.sessionId());
            assertEquals(Set.of(origin), denied.access().pendingOrigins());
            assertEquals(Set.of(SiteRegistrationFixture.ORIGIN), denied.access().allowedOrigins());
            fixture.clock.advance(Duration.ofMinutes(1));
            var updated = fixture.call(
                    "registration.origin",
                    new SiteRegistrationContracts.OriginRequest(active.sessionId(), 1, origin),
                    "allow");
            assertEquals(active.access().expiresAt(), updated.access().expiresAt());
            assertEquals(2, updated.access().generation());
            assertTrue(updated.access().pendingOrigins().isEmpty());
            assertThrows(
                    SecurityException.class,
                    () -> fixture.call(
                            "registration.origin",
                            new SiteRegistrationContracts.OriginRequest(active.sessionId(), 1, origin),
                            "stale"));
            var completed = fixture.call("registration.complete", fixture.confirmation(updated, false), "save");
            var site = new H2ManagedExtensionStore(fixture.database, fixture.clock)
                    .inTransaction(
                            new ExtensionId(BuiltinExtensionIds.SITE),
                            tx -> fixture.json.decode(
                                    tx.get(
                                                    "documents." + SiteRegistrationFixture.WORKSPACE,
                                                    completed
                                                            .completed()
                                                            .orElseThrow()
                                                            .siteId())
                                            .orElseThrow()
                                            .payload(),
                                    SiteContracts.Site.class));
            assertEquals(updated.access().allowedOrigins(), site.allowedOrigins());
            assertEquals(1, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void Worker缺少原生窗口能力时不开窗口或登记记录() throws Exception {
        try (var fixture = fixture()) {
            fixture.available = false;
            assertThrows(IllegalStateException.class, fixture::begin);
            assertEquals(0, fixture.worker.starts);
            assertEquals(0, fixture.siteCount());
        }
    }

    @Test
    void 启动失败记录终态而同身份重试不会重复启动() throws Exception {
        try (var fixture = fixture()) {
            fixture.worker.beginFailure = true;
            assertThrows(IllegalStateException.class, fixture::begin);
            assertEquals(State.FAILED, fixture.begin().state());
            assertEquals(1, fixture.worker.starts);
            assertTrue(fixture.worker.cancels > 0);
            assertEquals(0, fixture.siteCount());
        }
    }

    @Test
    void 超时和重启遗留会话不能取得新执行能力() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            fixture.clock.advance(Duration.ofMinutes(16));
            assertEquals(State.EXPIRED, fixture.status(active.sessionId()).state());
            String missing = UUID.randomUUID().toString();
            fixture.store.record(
                    SiteRegistrationFixture.WORKSPACE,
                    SiteRegistrationFixture.identity("orphan"),
                    new SiteRegistrationContracts.Session(
                            missing, State.ACTIVE, active.access(), active.page(), Optional.empty()));
            assertEquals(State.EXPIRED, fixture.status(missing).state());
            assertEquals(1, fixture.worker.starts);
            assertEquals(0, fixture.siteCount());
        }
    }

    @Test
    void 跨Workspace和篡改平台Scope不能读取或管理会话() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            var request = new SiteRegistrationContracts.SessionRequest(active.sessionId());
            assertThrows(
                    SecurityException.class,
                    () -> fixture.service.invoke(fixture.invocation(
                            WorkspaceId.parse("1527756d-4881-4a7f-aa87-f01f38919061"),
                            "registration.status",
                            request,
                            null)));
            var invocation =
                    fixture.invocation(SiteRegistrationFixture.WORKSPACE, "registration.cancel", request, "key");
            var altered = new com.javaclaw.extension.spi.IsolatedServiceInvocation(
                    invocation.caller(),
                    invocation.workspaceId(),
                    invocation.effectivePermissions(),
                    invocation.serviceId(),
                    invocation.request(),
                    invocation.cancellation(),
                    com.javaclaw.extension.spi.IsolatedServiceCallScope.empty());
            assertThrows(SecurityException.class, () -> fixture.service.invoke(altered));
            assertEquals(State.ACTIVE, fixture.status(active.sessionId()).state());
        }
    }

    @Test
    void Worker伪造租约或页面版本会关闭窗口而不写入任何秘密() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            fixture.worker.wrongLease = true;
            assertThrows(SecurityException.class, () -> fixture.status(active.sessionId()));
            assertEquals(State.FAILED, fixture.status(active.sessionId()).state());
            assertEquals(0, fixture.siteCount());
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 页面变化拒绝旧确认保存() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            var request =
                    new SiteRegistrationContracts.CompleteRequest(active.sessionId(), 1, 2, Optional.empty(), "站点");
            assertThrows(SecurityException.class, () -> fixture.call("registration.complete", request, "save"));
            assertEquals(0, fixture.siteCount());
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 同来源已注册拒绝覆盖且第二次登记密文一并回滚() throws Exception {
        try (var fixture = fixture()) {
            var first = fixture.begin();
            fixture.call("registration.complete", fixture.confirmation(first, false), "save");
            var second = fixture.call(
                    "registration.begin",
                    new SiteRegistrationContracts.BeginRequest(SiteRegistrationFixture.ORIGIN),
                    "begin2");
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.call("registration.complete", fixture.confirmation(second, true), "save2"));
            assertEquals(1, fixture.siteCount());
            assertEquals(1, fixture.vault.status().credentialCount());
            assertEquals(State.FAILED, fixture.status(second.sessionId()).state());
        }
    }

    @Test
    void 提交后丢失Worker回执只恢复已提交事实不重新消费秘密() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            fixture.worker.saveReplyLost = true;
            var completed = fixture.call("registration.complete", fixture.confirmation(active, false), "save");
            assertEquals(State.COMPLETED, completed.state());
            assertEquals(completed, fixture.status(active.sessionId()));
            assertEquals(1, fixture.worker.saves);
            assertEquals(1, fixture.siteCount());
        }
    }

    @Test
    void 事务内撤销导致网站账号密文回执全部回滚且清理明文() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            byte[] state = SiteRegistrationFixture.STORAGE.clone();
            byte[] credentials = "u\0p".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var identity = SiteRegistrationFixture.identity("fail-transaction");
            assertThrows(
                    SecurityException.class,
                    () -> fixture.store.complete(
                            SiteRegistrationFixture.WORKSPACE,
                            fixture.confirmation(active, true),
                            fixture.worker.current,
                            identity,
                            state,
                            credentials,
                            () -> {
                                throw new SecurityException("已撤销");
                            }));
            assertArrayEquals(new byte[state.length], state);
            assertArrayEquals(new byte[credentials.length], credentials);
            assertEquals(0, fixture.siteCount());
            assertEquals(0, fixture.vault.status().credentialCount());
            assertTrue(fixture.store.recover(identity).isEmpty());
        }
    }

    @Test
    void Broker逐请求检查来源并删除受控头且取消后拒绝网络() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            var request = new BrowserContracts.NetworkRequest(
                    SiteRegistrationFixture.ORIGIN.resolve("/account"),
                    "GET",
                    Map.of("Host", List.of("wrong"), "Cookie", List.of("secret-cookie")));
            var response = fixture.worker.network.exchange(request, new byte[0], new CancellationSource());
            assertEquals(200, response.response().statusCode());
            assertFalse(fixture.broker.requests.getFirst().headers().containsKey("host"));
            assertTrue(fixture.broker.requests.getFirst().headers().containsKey("cookie"));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.worker.network.exchange(
                            new BrowserContracts.NetworkRequest(
                                    URI.create("https://evil.example.com"), "GET", Map.of()),
                            new byte[0],
                            new CancellationSource()));
            fixture.call(
                    "registration.cancel", new SiteRegistrationContracts.SessionRequest(active.sessionId()), "cancel");
            assertThrows(
                    RuntimeException.class,
                    () -> fixture.worker.network.exchange(request, new byte[0], new CancellationSource()));
            assertEquals(1, fixture.broker.requests.size());
        }
    }

    @Test
    void 不能把其他来源密码保存为当前网站账号() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            var page = fixture.worker.current.page();
            var foreign = new SiteRegistrationContracts.CredentialCandidate(
                    "candidate", URI.create("https://idp.example.com"), "身份提供方");
            fixture.worker.current = new SiteRegistrationContracts.WorkerStatus(
                    active.sessionId(),
                    State.ACTIVE,
                    active.access(),
                    new SiteRegistrationContracts.Page(
                            page.pageRevision(), page.uri(), page.title(), List.of(foreign)));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.call("registration.complete", fixture.confirmation(active, true), "save"));
            assertEquals(0, fixture.siteCount());
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 私有导出期间租约到期不能提交网站() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            fixture.worker.beforeSave = () -> fixture.clock.advance(Duration.ofMinutes(16));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.call("registration.complete", fixture.confirmation(active, true), "save"));
            assertEquals(0, fixture.siteCount());
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void Worker主动关闭窗口只留下取消状态不创建网站() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            fixture.worker.current = new SiteRegistrationContracts.WorkerStatus(
                    active.sessionId(), State.CANCELLED, active.access(), active.page());
            assertEquals(State.CANCELLED, fixture.status(active.sessionId()).state());
            assertEquals(0, fixture.siteCount());
            assertTrue(fixture.worker.cancels > 0);
        }
    }

    @Test
    void 启动幂等身份绑定原始地址且聊天Scope不能借用设置页服务() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            assertThrows(
                    com.javaclaw.server.persistence.PersistenceException.class,
                    () -> fixture.call(
                            "registration.begin",
                            new SiteRegistrationContracts.BeginRequest(URI.create("https://other.example.com")),
                            "begin"));
            var invocation = fixture.invocation(
                    SiteRegistrationFixture.WORKSPACE,
                    "registration.cancel",
                    new SiteRegistrationContracts.SessionRequest(active.sessionId()),
                    "cancel");
            var scope = new com.javaclaw.extension.spi.IsolatedServiceCallScope(
                    Optional.of(com.javaclaw.api.ThreadId.random()), Optional.empty(), Optional.of("cancel"), 0);
            var fromChat = new com.javaclaw.extension.spi.IsolatedServiceInvocation(
                    invocation.caller(),
                    invocation.workspaceId(),
                    invocation.effectivePermissions(),
                    invocation.serviceId(),
                    invocation.request(),
                    invocation.cancellation(),
                    scope);
            assertThrows(SecurityException.class, () -> fixture.service.invoke(fromChat));
            assertEquals(State.ACTIVE, fixture.status(active.sessionId()).state());
            assertEquals(1, fixture.worker.starts);
        }
    }

    @Test
    void 取消清理抛错只记录失败且立刻切断网络() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            fixture.worker.cancelFailure = new IllegalStateException("原生进程树清理失败");
            RuntimeException failure = assertThrows(
                    IllegalStateException.class,
                    () -> fixture.call(
                            "registration.cancel",
                            new SiteRegistrationContracts.SessionRequest(active.sessionId()),
                            "cancel"));
            org.junit.jupiter.api.Assertions.assertSame(fixture.worker.cancelFailure, failure);
            assertEquals(State.FAILED, fixture.status(active.sessionId()).state());
            assertThrows(
                    RuntimeException.class,
                    () -> fixture.worker.network.exchange(
                            new BrowserContracts.NetworkRequest(SiteRegistrationFixture.ORIGIN, "GET", Map.of()),
                            new byte[0],
                            new CancellationSource()));
            assertEquals(0, fixture.siteCount());
        }
    }

    @Test
    void 到期清理返回失败不能宣告临时秘密已清除() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            fixture.worker.cancelState = State.FAILED;
            fixture.clock.advance(Duration.ofMinutes(16));
            // 维护线程可能先观察到期；无论哪条路径处理，都只能持久化失败。
            try {
                fixture.status(active.sessionId());
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("清理失败"));
            }
            assertEquals(State.FAILED, fixture.status(active.sessionId()).state());
            assertEquals(0, fixture.siteCount());
        }
    }

    @Test
    void 关闭宿主保留原生清理异常且失败记录可供恢复读取() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            fixture.worker.cancelFailure = new IllegalStateException("原生清理证据");
            var failure = assertThrows(IllegalStateException.class, fixture.service::close);
            org.junit.jupiter.api.Assertions.assertSame(fixture.worker.cancelFailure, failure);
            assertEquals(
                    State.FAILED,
                    fixture.store
                            .read(SiteRegistrationFixture.WORKSPACE, active.sessionId())
                            .orElseThrow()
                            .state());
        }
    }

    @Test
    void 取消已提交会话不改变完成事实或再次清理Worker() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            var completed = fixture.call("registration.complete", fixture.confirmation(active, false), "save");
            fixture.worker.cancelFailure = new IllegalStateException("不得再次调用");
            assertEquals(
                    completed,
                    fixture.call(
                            "registration.cancel",
                            new SiteRegistrationContracts.SessionRequest(active.sessionId()),
                            "cancel"));
            assertEquals(0, fixture.worker.cancels);
            assertEquals(State.COMPLETED, fixture.status(active.sessionId()).state());
        }
    }

    @Test
    void 完成登记后地址与来源索引以规范对象持久化且不保留登录查询参数() throws Exception {
        try (var fixture = fixture()) {
            var active = fixture.begin();
            var completed = fixture.call("registration.complete", fixture.confirmation(active, false), "save");
            String siteId = completed.completed().orElseThrow().siteId();
            new H2ManagedExtensionStore(fixture.database, fixture.clock)
                    .inTransaction(new ExtensionId(BuiltinExtensionIds.SITE), tx -> {
                        var address = tx.get("registration-addresses." + SiteRegistrationFixture.WORKSPACE, siteId)
                                .orElseThrow()
                                .payload();
                        assertEquals(
                                Optional.of("https://example.com/account"), fixture.json.textField(address, "uri"));
                        assertEquals(Optional.of("示例网站"), fixture.json.textField(address, "title"));
                        assertFalse(address.json().contains("token=secret"));
                        var origins = tx.list("registration-origins." + SiteRegistrationFixture.WORKSPACE, "", 10);
                        assertEquals(1, origins.size());
                        assertEquals(
                                Optional.of(siteId),
                                fixture.json.textField(origins.getFirst().payload(), "siteId"));
                        assertEquals(
                                Optional.of("https://example.com"),
                                fixture.json.textField(origins.getFirst().payload(), "origin"));
                        return null;
                    });
        }
    }

    private SiteRegistrationFixture fixture() throws Exception {
        return new SiteRegistrationFixture(temporaryDirectory.resolve("data-v6"));
    }
}
