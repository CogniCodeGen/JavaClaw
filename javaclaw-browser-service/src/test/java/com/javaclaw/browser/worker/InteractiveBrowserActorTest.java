package com.javaclaw.browser.worker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.microsoft.playwright.options.ServiceWorkerPolicy;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.client.BrowserActionResult;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserContracts.Operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserActorTest {
    @Test
    void 凭据同时校验后私有填入并遮罩页面且存储包含IndexedDB() {
        InteractivePlaywrightFixture fixture = new InteractivePlaywrightFixture();
        try (InteractiveBrowserActor actor = actor(fixture);
                BrowserActionResult opened = actor.open(new byte[0])) {
            var first = opened.observation().page();
            var target = new BrowserContracts.CredentialsTarget(
                    first.pageId(),
                    first.elements().get(0).reference(),
                    first.elements().get(1).reference());
            try (BrowserActionResult filled = actor.fillCredentials(
                    credentials(actor, target), "alice\0s3cret".getBytes(StandardCharsets.UTF_8))) {
                assertFalse(filled.observation().page().text().contains("alice"));
                assertFalse(filled.observation().page().text().contains("s3cret"));
                assertEquals("alice", fixture.pages.getFirst().user);
                assertEquals("s3cret", fixture.pages.getFirst().password);
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> actor.fillCredentials(credentials(actor, target), "x\0y".getBytes(StandardCharsets.UTF_8)));
            var denied = assertThrows(
                    IllegalStateException.class,
                    () -> actor.act(BrowserContracts.Action.simple(Operation.SCREENSHOT), new byte[0]));
            assertEquals("BROWSER_SCREENSHOT_PRIVATE_SESSION", denied.getMessage());
            assertTrue(new String(actor.saveState(), StandardCharsets.UTF_8).contains("indexedDB"));
            assertTrue(fixture.indexedDb);
            assertFalse(fixture.headless);
            assertEquals(ServiceWorkerPolicy.BLOCK, fixture.serviceWorkers);
        }
        assertEquals(1, fixture.closes);
    }

    @Test
    void 坐标从截图像素换算且滚动或控制权变化后禁止旧帧() throws Exception {
        InteractivePlaywrightFixture fixture = new InteractivePlaywrightFixture();
        try (InteractiveBrowserActor actor = actor(fixture);
                BrowserActionResult ignored = actor.open(new byte[0]);
                BrowserActionResult screenshot =
                        actor.act(BrowserContracts.Action.simple(Operation.SCREENSHOT), new byte[0])) {
            var frame = screenshot.observation().frame().orElseThrow();
            var click = coordinate(frame);
            try (BrowserActionResult clicked = actor.act(click, new byte[0])) {
                assertEquals(128, fixture.pages.getFirst().clickedX);
                assertEquals(90, fixture.pages.getFirst().clickedY);
            }
            assertThrows(IllegalStateException.class, () -> actor.act(click, new byte[0]));
            try (BrowserActionResult next =
                    actor.act(BrowserContracts.Action.simple(Operation.SCREENSHOT), new byte[0])) {
                fixture.pages.getFirst().scroll = 10;
                assertThrows(
                        IllegalStateException.class,
                        () -> actor.act(coordinate(next.observation().frame().orElseThrow()), new byte[0]));
            }
            actor.updateLease(lease(BrowserContracts.ControlMode.HUMAN, 2));
            assertThrows(IllegalStateException.class, () -> actor.act(click, new byte[0]));
            var denied = assertThrows(
                    IllegalStateException.class,
                    () -> actor.act(BrowserContracts.Action.simple(Operation.SCREENSHOT), new byte[0]));
            assertEquals("BROWSER_SCREENSHOT_PRIVATE_SESSION", denied.getMessage());
            assertTrue(fixture.pages.getFirst().masks > 0);
            actor.updateLease(lease(BrowserContracts.ControlMode.ASSISTANT, 3));
            assertThrows(
                    IllegalStateException.class,
                    () -> actor.act(BrowserContracts.Action.simple(Operation.SCREENSHOT), new byte[0]));
            actor.updateLease(lease(BrowserContracts.ControlMode.NONE, 4));
            assertTrue(
                    new String(actor.saveState(), StandardCharsets.UTF_8).contains("indexedDB"),
                    "取消网络租约后仍允许宿主私有保存，不恢复网页网络权");
            assertThrows(
                    IllegalStateException.class,
                    () -> actor.act(BrowserContracts.Action.simple(Operation.SNAPSHOT), new byte[0]));
        }
    }

    @Test
    void 分页最多八个且导航不允许越过Https授权来源() throws Exception {
        InteractivePlaywrightFixture fixture = new InteractivePlaywrightFixture();
        try (InteractiveBrowserActor actor = actor(fixture);
                BrowserActionResult ignored = actor.open(new byte[0])) {
            for (int index = 1; index < 8; index++) {
                try (BrowserActionResult tab =
                        actor.act(action(Operation.NEW_TAB, "https://docs.example.com/"), new byte[0])) {
                    assertEquals(index + 1, tab.observation().session().tabs().size());
                }
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> actor.act(action(Operation.NEW_TAB, "https://docs.example.com/"), new byte[0]));
            assertThrows(
                    IllegalStateException.class,
                    () -> actor.act(action(Operation.NAVIGATE, "https://other.example.com/"), new byte[0]));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> actor.act(action(Operation.NAVIGATE, "http://docs.example.com/"), new byte[0]));
            assertEquals(8, actor.view().tabs().size());
        }
    }

    @Test
    void 人工准备不读值且捕获必须仍属于同Origin同表单与冻结租约() throws Exception {
        InteractivePlaywrightFixture fixture = new InteractivePlaywrightFixture();
        try (InteractiveBrowserActor actor = actor(fixture);
                BrowserActionResult ignored = actor.open(new byte[0])) {
            var original = actor.view().lease();
            actor.updateLease(lease(BrowserContracts.ControlMode.HUMAN, 2));
            var current = actor.view().lease();
            var page = fixture.pages.getFirst();
            page.valueReads = 0;
            var forms = actor.prepare(
                    new InteractiveBrowserProtocol.FormsRequest(current, URI.create("https://docs.example.com")));
            assertEquals(1, forms.size());
            assertEquals("Sign in", forms.getFirst().label());
            assertEquals(0, page.valueReads, "准备阶段只读取字段标签与关联关系");
            page.user = "alice";
            page.password = "s3cret";
            var request = credentials(actor, forms.getFirst().target());
            assertEquals("alice\0s3cret", new String(actor.capture(request), StandardCharsets.UTF_8));
            page.sameForm = false;
            assertThrows(IllegalStateException.class, () -> actor.capture(request));
            page.sameForm = true;
            assertThrows(
                    IllegalStateException.class,
                    () -> actor.capture(new InteractiveBrowserProtocol.CredentialsRequest(
                            current,
                            URI.create("https://other.example.com"),
                            forms.getFirst().target())));
            assertThrows(
                    IllegalStateException.class,
                    () -> actor.act(
                            new InteractiveBrowserProtocol.ActionRequest(
                                    original, BrowserContracts.Action.simple(Operation.SNAPSHOT)),
                            new byte[0]));
            actor.updateLease(lease(BrowserContracts.ControlMode.HUMAN, 3));
            assertThrows(IllegalStateException.class, () -> actor.capture(request));
        }
    }

    private static InteractiveBrowserProtocol.CredentialsRequest credentials(
            InteractiveBrowserActor actor, BrowserContracts.CredentialsTarget target) {
        return new InteractiveBrowserProtocol.CredentialsRequest(
                actor.view().lease(), URI.create("https://docs.example.com"), target);
    }

    private static BrowserContracts.Action coordinate(BrowserContracts.Frame frame) {
        return new BrowserContracts.Action(
                Operation.CLICK_AT,
                new BrowserContracts.Target(frame.pageId(), "", frame.frameId()),
                new BrowserContracts.ActionInput(
                        "", Optional.of(new BrowserContracts.Point(256, 180)), Optional.empty(), Optional.empty()));
    }

    private static BrowserContracts.Action action(Operation operation, String value) {
        return new BrowserContracts.Action(
                operation, BrowserContracts.Target.current(), BrowserContracts.ActionInput.text(value));
    }

    private static InteractiveBrowserActor actor(InteractivePlaywrightFixture fixture) {
        var task = new BrowserContracts.OpenTask(
                UUID.randomUUID().toString(),
                new BrowserContracts.Owner(WorkspaceId.random(), ThreadId.random(), Optional.empty()),
                URI.create("https://docs.example.com/"),
                lease(BrowserContracts.ControlMode.ASSISTANT, 1));
        return new InteractiveBrowserActor(task, null, () -> fixture.playwright);
    }

    private static BrowserContracts.AccessLease lease(BrowserContracts.ControlMode mode, long generation) {
        return new BrowserContracts.AccessLease(
                mode,
                UUID.randomUUID().toString(),
                generation,
                Instant.now().plusSeconds(60),
                Set.of(URI.create("https://docs.example.com")));
    }
}
