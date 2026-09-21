package com.javaclaw.browser.worker;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.javaclaw.browser.protocol.BrowserRegistrationProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Packet;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.WorkerStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class RegistrationWorkerLoopTest {
    @Test
    void 登记循环返回实际状态并只通过私有字节返回登录态() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.browser.storage =
                    "{\"cookies\":[],\"origins\":[{\"origin\":\"https://docs.example.com\",\"indexedDB\":[]}]}";
            WorkerStatus opened = open(loop);
            var next = new BrowserContracts.AccessLease(
                    BrowserContracts.ControlMode.HUMAN,
                    "next",
                    2,
                    opened.access().expiresAt(),
                    opened.access().allowedOrigins());
            try (Packet updated = loop.request(InteractiveBrowserProtocol.LEASE, next)) {
                assertEquals(2, decode(loop, updated).access().generation());
            }
            WorkerStatus current;
            try (Packet status =
                    loop.request(InteractiveBrowserProtocol.STATUS, InteractiveWorkerLoopFixture.empty())) {
                current = decode(loop, status);
                assertEquals(0, status.frame().binaryBytes());
            }
            try (Packet saved = loop.request(
                    BrowserRegistrationProtocol.COMPLETE,
                    RegistrationBrowserActorTest.confirmation(current, Optional.empty()))) {
                assertTrue(saved.frame().error().isEmpty());
                var metadata =
                        loop.json.decode(saved.frame().payload(), BrowserRegistrationProtocol.PrivateResult.class);
                assertEquals(saved.bytes().length, metadata.stateBytes());
                assertEquals(0, metadata.credentialBytes());
                assertTrue(new String(saved.bytes(), StandardCharsets.UTF_8).contains("indexedDB"));
                assertFalse(saved.frame().payload().json().contains("indexedDB"));
            }
            assertFalse(loop.stopped(), "终态回复不能让原生Worker自行退出");
            loop.hostOutput.close();
            loop.awaitStopped();
            assertEquals(1, loop.browser.contextCloses);
        }
    }

    @Test
    void 导出前明确失败同样结束窗口且错误不回显任何参数() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            WorkerStatus opened = open(loop);
            var stale = new SiteRegistrationContracts.CompleteRequest(
                    opened.sessionId(), 99, opened.page().pageRevision(), Optional.empty(), "private-secret");
            try (Packet failed = loop.request(BrowserRegistrationProtocol.COMPLETE, stale)) {
                assertEquals(
                        Optional.of("BROWSER_STALE_OBSERVATION"), failed.frame().error());
                assertEquals("{}", failed.frame().payload().json());
                assertEquals(0, failed.bytes().length);
            }
            assertFalse(loop.stopped(), "终态回复不能让原生Worker自行退出");
            loop.hostOutput.close();
            loop.awaitStopped();
            assertEquals(1, loop.browser.closes);
        }
    }

    @Test
    void 只接受登记命令与空输入但错误不会授权助手操作() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            open(loop);
            try (Packet invalid =
                    loop.request(InteractiveBrowserProtocol.ACTION, InteractiveWorkerLoopFixture.empty())) {
                assertEquals(
                        Optional.of("BROWSER_UNSUPPORTED_REGISTRATION_COMMAND"),
                        invalid.frame().error());
            }
            try (Packet invalid = loop.request(
                    InteractiveBrowserProtocol.STATUS, InteractiveWorkerLoopFixture.empty(), new byte[] {1})) {
                assertEquals(
                        Optional.of("BROWSER_UNEXPECTED_PRIVATE_INPUT"),
                        invalid.frame().error());
            }
            try (Packet malformed =
                    loop.request(InteractiveBrowserProtocol.LEASE, InteractiveWorkerLoopFixture.empty())) {
                assertEquals(
                        Optional.of("BROWSER_ACTION_FAILED"), malformed.frame().error());
            }
            try (Packet closed = loop.request(InteractiveBrowserProtocol.CLOSE, InteractiveWorkerLoopFixture.empty())) {
                assertEquals(
                        SiteRegistrationContracts.State.CANCELLED,
                        decode(loop, closed).state());
            }
            assertFalse(loop.stopped(), "终态回复不能让原生Worker自行退出");
            loop.hostOutput.close();
            loop.awaitStopped();
        }
    }

    @Test
    void 启动失败脱敏且输入通道关闭时回收登记窗口() throws Exception {
        try (var loop = new InteractiveWorkerLoopFixture()) {
            loop.startRegistration(() -> {
                throw new IllegalStateException("password=private-secret");
            });
            try (Packet failed = loop.receive()) {
                assertEquals(Optional.of("BROWSER_START_FAILED"), failed.frame().error());
                assertEquals("{}", failed.frame().payload().json());
            }
            assertFalse(loop.stopped(), "终态回复不能让原生Worker自行退出");
            loop.hostOutput.close();
            loop.awaitStopped();
        }
        try (var loop = new InteractiveWorkerLoopFixture()) {
            open(loop);
            loop.hostOutput.close();
            loop.awaitStopped();
            assertEquals(1, loop.browser.closes);
        }
    }

    private static WorkerStatus open(InteractiveWorkerLoopFixture loop) throws Exception {
        loop.startRegistration(() -> loop.browser.playwright);
        try (Packet opened = loop.receive()) {
            assertEquals(1, opened.frame().id());
            assertTrue(opened.frame().error().isEmpty());
            return decode(loop, opened);
        }
    }

    private static WorkerStatus decode(InteractiveWorkerLoopFixture loop, Packet packet) {
        return loop.json.decode(packet.frame().payload(), WorkerStatus.class);
    }
}
