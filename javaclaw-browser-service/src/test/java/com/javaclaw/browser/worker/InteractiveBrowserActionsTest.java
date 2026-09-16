package com.javaclaw.browser.worker;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserContracts.Operation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserActionsTest {
    @Test
    void 导航和分页只作用于指定页面且不能关闭最后一页() throws Exception {
        try (var fixture = new InteractiveActionFixture()) {
            for (var operation : List.of(Operation.BACK, Operation.FORWARD, Operation.RELOAD)) {
                assertTrue(fixture.actions
                        .execute(InteractiveActionFixture.action(operation, ""), new byte[0])
                        .isEmpty());
            }
            assertTrue(fixture.fake().commands.containsAll(List.of("goBack", "goForward", "reload")));
            fixture.actions.execute(
                    InteractiveActionFixture.action(Operation.NAVIGATE, "https://docs.example.com/next"), new byte[0]);
            assertEquals("https://docs.example.com/next", fixture.fake().uri);
            String original = fixture.pages.id(fixture.page);
            fixture.actions.execute(
                    InteractiveActionFixture.action(Operation.NEW_TAB, "https://docs.example.com/second"), new byte[0]);
            assertEquals(2, fixture.pages.tabs().size());
            fixture.actions.execute(
                    new BrowserContracts.Action(
                            Operation.SWITCH_TAB,
                            new BrowserContracts.Target(original, "", ""),
                            BrowserContracts.ActionInput.text("")),
                    new byte[0]);
            assertEquals(fixture.page, fixture.pages.page(""));
            fixture.actions.execute(InteractiveActionFixture.action(Operation.CLOSE_TAB, ""), new byte[0]);
            assertTrue(fixture.fake().closed);
            assertEquals(1, fixture.pages.tabs().size());
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.actions.execute(
                            InteractiveActionFixture.action(Operation.CLOSE_TAB, ""), new byte[0]));
        }
    }

    @Test
    void 元素操作依赖本次观察引用并保留输入和受限按键语义() throws Exception {
        try (var fixture = new InteractiveActionFixture()) {
            var old = fixture.element(Operation.CLICK, 0, "");
            fixture.actions.execute(fixture.element(Operation.FILL, 0, "draft"), new byte[0]);
            assertEquals("draft", fixture.fake().user);
            assertThrows(IllegalStateException.class, () -> fixture.actions.execute(old, new byte[0]));
            for (var operation : List.of(
                    Operation.CLICK, Operation.DOUBLE_CLICK, Operation.SELECT, Operation.HOVER, Operation.PRESS)) {
                fixture.actions.execute(fixture.element(operation, 0, "Enter"), new byte[0]);
            }
            fixture.actions.execute(fixture.element(Operation.CHECK, 0, "true"), new byte[0]);
            fixture.actions.execute(fixture.element(Operation.CHECK, 0, "false"), new byte[0]);
            assertEquals(1, fixture.fake().clicks);
            assertTrue(fixture.fake()
                    .commands
                    .containsAll(List.of("dblclick", "selectOption", "hover", "press", "setChecked")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.actions.execute(fixture.element(Operation.CHECK, 0, "yes"), new byte[0]));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.actions.execute(fixture.element(Operation.PRESS, 0, "Control+R"), new byte[0]));
        }
    }

    @Test
    void 上传只接受当前私有字节和安全文件描述() throws Exception {
        try (var fixture = new InteractiveActionFixture()) {
            byte[] bytes = "attachment bytes".getBytes(StandardCharsets.UTF_8);
            var input = new BrowserContracts.ActionInput(
                    "",
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new BrowserContracts.FileSpec("report.txt", "text/plain")));
            fixture.actions.execute(fixture.element(Operation.UPLOAD, 0, input), bytes);
            assertArrayEquals(bytes, fixture.fake().uploaded.buffer);
            assertEquals("report.txt", fixture.fake().uploaded.name);
            assertEquals("text/plain", fixture.fake().uploaded.mimeType);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.actions.execute(
                            fixture.element(Operation.UPLOAD, 0, input),
                            new byte[BrowserContracts.MAXIMUM_ARTIFACT_BYTES + 1]));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.actions.execute(InteractiveActionFixture.action(Operation.SNAPSHOT, ""), bytes));
        }
    }

    @Test
    void 密码禁止普通填入且私有填入永久限制截图并遮罩文字() throws Exception {
        try (var fixture = new InteractiveActionFixture()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.actions.execute(fixture.element(Operation.FILL, 1, "plaintext"), new byte[0]));
            for (byte[] invalid : List.of(new byte[0], new byte[65_537], new byte[] {'a', 0, 'b'})) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.actions.execute(fixture.element(Operation.FILL_SECRET, 1, ""), invalid));
            }
            fixture.actions.execute(
                    fixture.element(Operation.FILL_SECRET, 1, ""), "password-opaque".getBytes(StandardCharsets.UTF_8));
            assertEquals("password-opaque", fixture.fake().password);
            assertFalse(fixture.pages.snapshot(fixture.page).text().contains("password-opaque"));
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.actions.execute(
                            InteractiveActionFixture.action(Operation.SCREENSHOT, ""), new byte[0]));
        }
    }

    @Test
    void 等待和滚动都有界且异常输入不会执行页面动作() throws Exception {
        try (var fixture = new InteractiveActionFixture()) {
            for (String valid : List.of("0", "5000")) {
                fixture.actions.execute(InteractiveActionFixture.action(Operation.WAIT, valid), new byte[0]);
            }
            for (String invalid : List.of("-1", "5001", "none")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.actions.execute(
                                InteractiveActionFixture.action(Operation.WAIT, invalid), new byte[0]));
            }
            fixture.actions.execute(InteractiveActionFixture.action(Operation.SCROLL, "-10000,10000"), new byte[0]);
            assertEquals(-10000, fixture.fake().wheelX);
            assertEquals(10000, fixture.fake().wheelY);
            for (String invalid : List.of("1", "1,2,3", "NaN,1", "1,Infinity", "10001,0", "0,-10001", "x,2")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.actions.execute(
                                InteractiveActionFixture.action(Operation.SCROLL, invalid), new byte[0]));
            }
            assertEquals(-10000, fixture.fake().wheelX);
        }
    }

    @Test
    void 拖拽使用有效截图帧且越界与旧帧都不触发按下() throws Exception {
        try (var fixture = new InteractiveActionFixture()) {
            fixture.pages.snapshot(fixture.page);
            try (var image = fixture.actions
                    .execute(InteractiveActionFixture.action(Operation.SCREENSHOT, ""), new byte[0])
                    .orElseThrow()) {
                var frame = fixture.pages.frame(fixture.page, image.bytes(), fixture.lease.generation());
                var drag = fixture.drag(frame, new BrowserContracts.Point(512, 360));
                fixture.actions.execute(drag, new byte[0]);
                assertTrue(fixture.fake().commands.containsAll(List.of("mouse.move", "mouse.down", "mouse.up")));
                assertFalse(fixture.fake().mouseHeld);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.actions.execute(
                                fixture.drag(frame, new BrowserContracts.Point(2560, 1800)), new byte[0]));
                fixture.fake().dom++;
                assertThrows(IllegalStateException.class, () -> fixture.actions.execute(drag, new byte[0]));
                assertFalse(fixture.fake().mouseHeld);
            }
            assertTrue(fixture.actions
                    .execute(InteractiveActionFixture.action(Operation.SNAPSHOT, ""), new byte[0])
                    .isEmpty());
        }
    }
}
