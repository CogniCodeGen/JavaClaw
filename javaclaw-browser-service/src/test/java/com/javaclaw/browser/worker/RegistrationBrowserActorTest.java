package com.javaclaw.browser.worker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.microsoft.playwright.options.ServiceWorkerPolicy;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.WorkerStatus;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationBrowserActorTest {
    private static final URI ORIGIN = URI.create("https://docs.example.com");

    @Test
    void 跳转后保留本次输入但公开信息脱敏且只一次导出最终来源状态() {
        InteractivePlaywrightFixture browser = new InteractivePlaywrightFixture();
        browser.storage = """
                {"cookies":[{"domain":".example.com","name":"a","value":"s"},
                {"domain":"login.other.com","name":"b","value":"other"}],
                "origins":[{"origin":"https://docs.example.com","indexedDB":[]},
                {"origin":"https://login.other.com","localStorage":[]}]}
                """;
        try (RegistrationBrowserActor actor = actor(browser)) {
            WorkerStatus opened = actor.open();
            var page = browser.pages.getFirst();
            capture(browser, page, "document:1", "alice", "private-pass");
            page.page.navigate("https://docs.example.com/dashboard?token=secret#private");
            page.body = "Welcome alice private-pass";
            WorkerStatus current = actor.view();
            assertEquals(
                    URI.create("https://docs.example.com/dashboard"),
                    current.page().uri().orElseThrow());
            assertTrue(current.page().pageRevision() > opened.page().pageRevision());
            assertEquals(1, current.page().candidates().size());
            String publicJson = new CanonicalJson().encode(current).json();
            assertFalse(publicJson.contains("alice"));
            assertFalse(publicJson.contains("private-pass"));
            assertFalse(publicJson.contains("token="));
            assertFalse(browser.headless);
            assertEquals(ServiceWorkerPolicy.BLOCK, browser.serviceWorkers);
            var request = confirmation(
                    current, Optional.of(current.page().candidates().getFirst().id()));
            try (RegistrationExport result = actor.complete(request)) {
                byte[] bytes = result.bytes();
                int stateBytes = result.metadata().stateBytes();
                String storage = new String(bytes, 0, stateBytes, StandardCharsets.UTF_8);
                assertTrue(storage.contains(".example.com"));
                assertTrue(storage.contains("indexedDB"));
                assertFalse(storage.contains("login.other.com"));
                assertEquals(
                        "alice\0private-pass",
                        new String(bytes, stateBytes, bytes.length - stateBytes, StandardCharsets.UTF_8));
                result.close();
                assertArrayEquals(new byte[bytes.length], result.bytes());
                Arrays.fill(bytes, (byte) 0);
            }
            assertTrue(browser.indexedDb);
            assertEquals(1, browser.closes);
            assertThrows(IllegalStateException.class, () -> actor.complete(request));
        }
    }

    @Test
    void 无可识别密码表单可仅保存登录态且来源切换后不提供其他来源候选() {
        InteractivePlaywrightFixture browser = new InteractivePlaywrightFixture();
        try (RegistrationBrowserActor actor = actor(browser)) {
            WorkerStatus original = actor.open();
            var page = browser.pages.getFirst();
            capture(browser, page, "form1", "first-user", "first-secret");
            URI other = URI.create("https://login.example.net");
            var lease = new BrowserContracts.AccessLease(
                    BrowserContracts.ControlMode.HUMAN,
                    "next",
                    2,
                    original.access().expiresAt(),
                    Set.of(ORIGIN, other));
            actor.updateLease(lease);
            page.page.navigate(other + "/signed-in");
            WorkerStatus next = actor.view();
            assertTrue(next.page().candidates().isEmpty());
            try (RegistrationExport result = actor.complete(confirmation(next, Optional.empty()))) {
                assertEquals(0, result.metadata().credentialBytes());
                assertTrue(result.metadata().stateBytes() > 0);
            }
        }
    }

    @Test
    void 保存期间页面再次导航则拒绝全部导出并销毁临时窗口() {
        InteractivePlaywrightFixture browser = new InteractivePlaywrightFixture();
        try (RegistrationBrowserActor actor = actor(browser)) {
            WorkerStatus opened = actor.open();
            browser.beforeStorage = () -> browser.pages.getFirst().page.navigate(ORIGIN + "/changed");
            var failed = assertThrows(
                    IllegalStateException.class, () -> actor.complete(confirmation(opened, Optional.empty())));
            assertEquals("BROWSER_STALE_OBSERVATION", failed.getMessage());
            assertEquals(1, browser.contextCloses);
            assertEquals(SiteRegistrationContracts.State.CANCELLED, actor.view().state());
        }
    }

    @Test
    void 相同显示地址的查询变化和同址重新导航都使确认失效() {
        InteractivePlaywrightFixture browser = new InteractivePlaywrightFixture();
        try (RegistrationBrowserActor actor = actor(browser)) {
            WorkerStatus first = actor.open();
            browser.pages.getFirst().uri = "https://docs.example.com/start?new=1#route";
            WorkerStatus query = actor.view();
            assertEquals(first.page().uri(), query.page().uri());
            assertTrue(query.page().pageRevision() > first.page().pageRevision());
            browser.pages.getFirst().page.navigate(browser.pages.getFirst().uri);
            assertThrows(IllegalStateException.class, () -> actor.complete(confirmation(query, Optional.empty())));
        }
    }

    @Test
    void 候选被替换后旧身份和旧页面确认不能静默使用新密码() {
        InteractivePlaywrightFixture browser = new InteractivePlaywrightFixture();
        try (RegistrationBrowserActor actor = actor(browser)) {
            actor.open();
            var page = browser.pages.getFirst();
            capture(browser, page, "form1", "alice", "one");
            WorkerStatus first = actor.view();
            capture(browser, page, "form1", "alice", "two");
            WorkerStatus second = actor.view();
            assertFalse(first.page().candidates().equals(second.page().candidates()));
            assertThrows(
                    IllegalStateException.class,
                    () -> actor.complete(confirmation(
                            first,
                            Optional.of(first.page().candidates().getFirst().id()))));
            assertThrows(
                    IllegalStateException.class,
                    () -> actor.complete(confirmation(
                            second,
                            Optional.of(first.page().candidates().getFirst().id()))));
        }
    }

    @Test
    void 新窗口选择与关闭回退更新确认且全部关窗立即清除秘密() {
        InteractivePlaywrightFixture browser = new InteractivePlaywrightFixture();
        try (RegistrationBrowserActor actor = actor(browser)) {
            actor.open();
            var first = browser.pages.getFirst();
            var popup = browser.context.newPage();
            popup.navigate(ORIGIN + "/popup");
            assertEquals("/popup", actor.view().page().uri().orElseThrow().getPath());
            browser.bindings.get("__javaclawRegistrationFocus").call(browser.source(first));
            assertEquals("/start", actor.view().page().uri().orElseThrow().getPath());
            first.page.close();
            assertEquals("/popup", actor.view().page().uri().orElseThrow().getPath());
            popup.close();
            assertEquals(SiteRegistrationContracts.State.CANCELLED, actor.view().state());
            assertEquals(1, browser.closes);
            actor.pump();
        }
    }

    @Test
    void 浏览器断连或租约到期各自关闭并保留准确终态() throws InterruptedException {
        InteractivePlaywrightFixture browser = new InteractivePlaywrightFixture();
        try (RegistrationBrowserActor actor = actor(browser)) {
            actor.open();
            browser.connected = false;
            assertEquals(SiteRegistrationContracts.State.FAILED, actor.view().state());
        }
        InteractivePlaywrightFixture expiring = new InteractivePlaywrightFixture();
        var task = task(Instant.now().plusMillis(500));
        try (RegistrationBrowserActor actor = new RegistrationBrowserActor(task, null, () -> expiring.playwright)) {
            actor.open();
            Thread.sleep(550);
            actor.pump();
            assertEquals(SiteRegistrationContracts.State.EXPIRED, actor.view().state());
            assertThrows(IllegalStateException.class, () -> actor.updateLease(task.lease()));
        }
    }

    @Test
    void 租约更新必须维持人工模式和原截止且当前地址不在授权内不能完成() {
        InteractivePlaywrightFixture browser = new InteractivePlaywrightFixture();
        try (RegistrationBrowserActor actor = actor(browser)) {
            WorkerStatus opened = actor.open();
            for (var mode : List.of(BrowserContracts.ControlMode.NONE, BrowserContracts.ControlMode.ASSISTANT)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> actor.updateLease(new BrowserContracts.AccessLease(
                                mode, "next", 2, opened.access().expiresAt(), Set.of(ORIGIN))));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> actor.updateLease(new BrowserContracts.AccessLease(
                            BrowserContracts.ControlMode.HUMAN,
                            "next",
                            1,
                            opened.access().expiresAt(),
                            Set.of(ORIGIN))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> actor.updateLease(new BrowserContracts.AccessLease(
                            BrowserContracts.ControlMode.HUMAN,
                            "next",
                            2,
                            Instant.now().plusSeconds(500),
                            Set.of(ORIGIN))));
            browser.pages.getFirst().uri = "https://unapproved.example.com/private";
            WorkerStatus unknown = actor.view();
            assertTrue(unknown.page().uri().isEmpty());
            assertThrows(IllegalStateException.class, () -> actor.complete(confirmation(unknown, Optional.empty())));
        }
    }

    private static RegistrationBrowserActor actor(InteractivePlaywrightFixture browser) {
        return new RegistrationBrowserActor(task(Instant.now().plusSeconds(60)), null, () -> browser.playwright);
    }

    static SiteRegistrationContracts.WorkerTask task(Instant deadline) {
        return new SiteRegistrationContracts.WorkerTask(
                UUID.randomUUID().toString(),
                WorkspaceId.random(),
                URI.create("https://docs.example.com/start?token=private#route"),
                new BrowserContracts.AccessLease(
                        BrowserContracts.ControlMode.HUMAN, "registration", 1, deadline, Set.of(ORIGIN)));
    }

    static SiteRegistrationContracts.CompleteRequest confirmation(WorkerStatus status, Optional<String> credential) {
        return new SiteRegistrationContracts.CompleteRequest(
                status.sessionId(), status.access().generation(), status.page().pageRevision(), credential, "Example");
    }

    private static void capture(
            InteractivePlaywrightFixture browser,
            InteractivePlaywrightFixture.FakePage page,
            String form,
            String username,
            String password) {
        browser.bindings.get("__javaclawRegistrationInput").call(browser.source(page), form, username, password);
    }
}
