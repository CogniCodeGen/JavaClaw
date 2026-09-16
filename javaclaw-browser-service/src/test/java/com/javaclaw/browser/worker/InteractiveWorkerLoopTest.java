package com.javaclaw.browser.worker;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Packet;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserContracts.ControlMode;
import com.javaclaw.builtin.contracts.BrowserContracts.Operation;
import com.javaclaw.builtin.contracts.BrowserContracts.SessionState;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class InteractiveWorkerLoopTest {
    @Test
    void 私有循环串行返回状态快照和截图且关闭只回收一次() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.start();
            var opened = loop.opened();
            try (Packet status =
                    loop.request(InteractiveBrowserProtocol.STATUS, InteractiveWorkerLoopFixture.empty())) {
                assertEquals(
                        opened.session(),
                        loop.json.decode(status.frame().payload(), BrowserContracts.SessionView.class));
                assertEquals(0, status.frame().binaryBytes());
            }
            try (Packet snapshot =
                    loop.request(InteractiveBrowserProtocol.ACTION, action(loop.original, Operation.SNAPSHOT))) {
                assertEquals(Optional.empty(), snapshot.frame().error());
                assertEquals(
                        "Login",
                        loop.json
                                .decode(snapshot.frame().payload(), BrowserContracts.Observation.class)
                                .page()
                                .text());
                assertEquals(0, snapshot.bytes().length);
            }
            try (Packet screenshot =
                    loop.request(InteractiveBrowserProtocol.ACTION, action(loop.original, Operation.SCREENSHOT))) {
                var observed = loop.json.decode(screenshot.frame().payload(), BrowserContracts.Observation.class);
                assertTrue(observed.frame().isPresent());
                assertEquals(24, screenshot.frame().binaryBytes());
                assertEquals(24, screenshot.bytes().length);
                assertFalse(screenshot.frame().payload().json().contains("base64"));
            }
            closeNormally(loop);
            assertTrue(loop.browser.pages.getFirst().closed);
            assertEquals(1, loop.browser.contextCloses);
            assertEquals(1, loop.browser.closes);
        }
    }

    @Test
    void 租约更新拒绝旧命令且停用后只能私有保存并能继续关闭() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.start();
            loop.opened();
            var next = InteractiveWorkerLoopFixture.lease(ControlMode.ASSISTANT, 2);
            try (Packet updated = loop.request(InteractiveBrowserProtocol.LEASE, next)) {
                assertEquals(
                        next,
                        loop.json
                                .decode(updated.frame().payload(), BrowserContracts.SessionView.class)
                                .lease());
            }
            try (Packet stale =
                    loop.request(InteractiveBrowserProtocol.ACTION, action(loop.original, Operation.SNAPSHOT))) {
                failure(stale, "BROWSER_STALE_OBSERVATION");
            }
            try (Packet duplicate = loop.request(InteractiveBrowserProtocol.LEASE, next)) {
                failure(duplicate, "BROWSER_ACTION_FAILED");
            }
            var none = InteractiveWorkerLoopFixture.lease(ControlMode.NONE, 3);
            try (Packet updated = loop.request(InteractiveBrowserProtocol.LEASE, none)) {
                assertEquals(Optional.empty(), updated.frame().error());
            }
            try (Packet denied = loop.request(InteractiveBrowserProtocol.ACTION, action(none, Operation.SNAPSHOT))) {
                failure(denied, "BROWSER_LEASE_INACTIVE");
            }
            try (Packet saved = loop.request(InteractiveBrowserProtocol.SAVE, InteractiveWorkerLoopFixture.empty())) {
                assertTrue(new String(saved.bytes(), StandardCharsets.UTF_8).contains("indexedDB"));
                assertFalse(saved.frame().payload().json().contains("indexedDB"));
                assertTrue(loop.browser.indexedDb);
            }
            closeNormally(loop);
        }
    }

    @Test
    void 凭据只经二进制填入与捕获且人工准备不读取值() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.start();
            var first = loop.opened().page();
            var target = new BrowserContracts.CredentialsTarget(
                    first.pageId(),
                    first.elements().get(0).reference(),
                    first.elements().get(1).reference());
            var request = credentials(loop.original, target);
            byte[] secret = "alice\0private-secret".getBytes(StandardCharsets.UTF_8);
            try (Packet filled = loop.request(InteractiveBrowserProtocol.FILL_CREDENTIALS, request, secret)) {
                assertEquals(Optional.empty(), filled.frame().error());
                noSecrets(filled);
                assertEquals(0, filled.bytes().length);
                assertEquals("alice", loop.browser.pages.getFirst().user);
                assertEquals("private-secret", loop.browser.pages.getFirst().password);
            }
            var human = InteractiveWorkerLoopFixture.lease(ControlMode.HUMAN, 2);
            try (Packet lease = loop.request(InteractiveBrowserProtocol.LEASE, human)) {
                assertEquals(Optional.empty(), lease.frame().error());
            }
            var form = prepare(loop, human);
            try (Packet captured =
                    loop.request(InteractiveBrowserProtocol.CAPTURE, credentials(human, form.target()))) {
                noSecrets(captured);
                assertArrayEquals(secret, captured.bytes());
                assertEquals(secret.length, captured.frame().binaryBytes());
            }
            try (Packet wrongOwner = loop.request(
                    InteractiveBrowserProtocol.FILL_CREDENTIALS, credentials(human, form.target()), secret)) {
                failure(wrongOwner, "BROWSER_CONTROL_MODE_CHANGED");
            }
            closeNormally(loop);
        }
    }

    @Test
    void 未知命令与非法负载脱敏失败且不会中断后续命令() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.start();
            loop.opened();
            try (Packet unknown = loop.request("unknown", InteractiveWorkerLoopFixture.empty(), new byte[] {1, 2})) {
                failure(unknown, "BROWSER_ACTION_FAILED");
            }
            try (Packet malformed = loop.request(
                    InteractiveBrowserProtocol.ACTION, new CanonicalPayload("{\"action\":\"private-secret\"}"))) {
                failure(malformed, "BROWSER_ACTION_FAILED");
            }
            try (Packet status =
                    loop.request(InteractiveBrowserProtocol.STATUS, InteractiveWorkerLoopFixture.empty())) {
                assertEquals(
                        SessionState.OPEN,
                        loop.json
                                .decode(status.frame().payload(), BrowserContracts.SessionView.class)
                                .state());
            }
            closeNormally(loop);
        }
    }

    @Test
    void 启动外部实现失败不泄露异常正文且回收已经创建的资源() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        Playwright failed = InteractivePlaywrightFixture.proxy(Playwright.class, (target, method, args) -> {
            if (method.getName().equals("chromium")) {
                throw new IllegalStateException("private-secret /private/account.json");
            }
            if (method.getName().equals("close")) {
                closed.incrementAndGet();
            }
            return null;
        });
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.start(() -> failed, new byte[0]);
            try (Packet result = loop.receive()) {
                assertEquals(1, result.frame().id());
                failure(result, "BROWSER_START_FAILED");
                noSecrets(result);
            }
            loop.awaitStopped();
            assertEquals(1, closed.get());
            assertTrue(loop.browser.pages.isEmpty());
        }
    }

    @Test
    void 私有帧长度不匹配终止循环且不执行不完整输入() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.start();
            loop.opened();
            var invalid = loop.frame(
                    InteractiveBrowserProtocol.Kind.COMMAND,
                    InteractiveBrowserProtocol.FILL_CREDENTIALS,
                    InteractiveWorkerLoopFixture.empty(),
                    2);
            BrowserFrameIo.writeJson(loop.hostOutput, loop.json, invalid);
            BrowserFrameIo.writeBinary(loop.hostOutput, new byte[] {7}, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            loop.awaitStopped();
            assertEquals("", loop.browser.pages.getFirst().user);
            assertEquals("", loop.browser.pages.getFirst().password);
            assertTrue(loop.browser.pages.getFirst().closed);
            assertEquals(1, loop.browser.contextCloses);
            assertEquals(1, loop.browser.closes);
        }
    }

    @Test
    void 宿主断开或发送错误方向帧都回收页面() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.start();
            loop.opened();
            loop.hostOutput.close();
            loop.awaitStopped();
            assertEquals(1, loop.browser.closes);
            assertEquals(1, loop.browser.contextCloses);
            assertTrue(loop.browser.pages.getFirst().closed);
        }
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.start();
            loop.opened();
            var invalid =
                    loop.frame(InteractiveBrowserProtocol.Kind.REPLY, "", InteractiveWorkerLoopFixture.empty(), 0);
            BrowserFrameIo.writeJson(loop.hostOutput, loop.json, invalid);
            loop.awaitStopped();
            assertEquals(1, loop.browser.closes);
            assertEquals(1, loop.browser.contextCloses);
            assertTrue(loop.browser.pages.getFirst().closed);
        }
    }

    @Test
    void 最后页面不能通过普通关闭分页绕过会话关闭() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.start();
            loop.opened();
            try (Packet closed =
                    loop.request(InteractiveBrowserProtocol.ACTION, action(loop.original, Operation.CLOSE_TAB))) {
                failure(closed, "BROWSER_ACTION_FAILED");
                assertFalse(loop.browser.pages.getFirst().closed);
            }
            closeNormally(loop);
            assertEquals(1, loop.browser.closes);
        }
    }

    private static BrowserContracts.LoginForm prepare(
            InteractiveWorkerLoopFixture loop, BrowserContracts.AccessLease human) throws Exception {
        loop.browser.pages.getFirst().valueReads = 0;
        try (Packet prepared = loop.request(
                InteractiveBrowserProtocol.PREPARE_CREDENTIALS,
                new InteractiveBrowserProtocol.FormsRequest(human, InteractiveWorkerLoopFixture.ORIGIN))) {
            noSecrets(prepared);
            assertEquals(0, loop.browser.pages.getFirst().valueReads);
            assertEquals(0, prepared.bytes().length);
            return loop.json
                    .decode(prepared.frame().payload(), InteractiveBrowserProtocol.FormsResult.class)
                    .forms()
                    .getFirst();
        }
    }

    private static InteractiveBrowserProtocol.ActionRequest action(
            BrowserContracts.AccessLease lease, Operation operation) {
        return new InteractiveBrowserProtocol.ActionRequest(lease, BrowserContracts.Action.simple(operation));
    }

    private static InteractiveBrowserProtocol.CredentialsRequest credentials(
            BrowserContracts.AccessLease lease, BrowserContracts.CredentialsTarget target) {
        return new InteractiveBrowserProtocol.CredentialsRequest(lease, InteractiveWorkerLoopFixture.ORIGIN, target);
    }

    private static void closeNormally(InteractiveWorkerLoopFixture loop) throws Exception {
        try (Packet closed = loop.request(InteractiveBrowserProtocol.CLOSE, InteractiveWorkerLoopFixture.empty())) {
            assertEquals(
                    SessionState.CLOSED,
                    loop.json
                            .decode(closed.frame().payload(), BrowserContracts.SessionView.class)
                            .state());
            assertEquals(0, closed.bytes().length);
        }
        loop.awaitStopped();
    }

    private static void failure(Packet packet, String code) {
        assertEquals(Optional.of(code), packet.frame().error());
        assertEquals("{}", packet.frame().payload().json());
        assertEquals(0, packet.frame().binaryBytes());
        assertEquals(0, packet.bytes().length);
    }

    private static void noSecrets(Packet packet) {
        assertFalse(packet.frame().payload().json().contains("alice"));
        assertFalse(packet.frame().payload().json().contains("private-secret"));
    }
}
