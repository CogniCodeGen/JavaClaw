package com.javaclaw.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.springframework.ai.tool.annotation.Tool;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class BrowserToolCompositionTest {

    private static final Set<String> EXPECTED_TOOL_NAMES =
            Set.of(
                    "web_navigate",
                    "site_select_account",
                    "site_login_interactive",
                    "site_login_now",
                    "site_fill_password",
                    "site_save_session",
                    "site_clear_session",
                    "web_go_back",
                    "web_go_forward",
                    "web_reload",
                    "web_snapshot",
                    "web_screenshot",
                    "web_screenshot_annotated",
                    "web_click",
                    "web_dblclick",
                    "web_fill",
                    "web_type",
                    "web_hover",
                    "web_select",
                    "web_check",
                    "web_focus",
                    "web_upload",
                    "web_drag",
                    "web_press_key",
                    "web_scroll",
                    "web_scroll_to_element",
                    "web_get_text",
                    "web_get_html",
                    "web_get_attribute",
                    "web_get_url",
                    "web_get_title",
                    "web_get_value",
                    "web_get_count",
                    "web_is_visible",
                    "web_is_enabled",
                    "web_is_checked",
                    "web_wait_for_element",
                    "web_wait_for_text",
                    "web_wait_for_url",
                    "web_wait_for_load",
                    "web_tab_new",
                    "web_tab_list",
                    "web_tab_close",
                    "web_tab_switch",
                    "web_eval_js",
                    "web_cookie_get",
                    "web_cookie_set",
                    "web_cookie_clear",
                    "web_save_pdf",
                    "web_mouse_move",
                    "web_mouse_click_at",
                    "web_dialog_handle",
                    "web_set_viewport");

    @Test
    void splitComponentsPreserveTheCompletePublicToolContract() {
        Set<String> actual =
                Stream.of(
                                BrowserSiteTools.class,
                                BrowserPageTools.class,
                                BrowserReadTools.class,
                                BrowserSessionTools.class)
                        .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                        .map(Method::getDeclaredAnnotations)
                        .flatMap(Arrays::stream)
                        .filter(Tool.class::isInstance)
                        .map(Tool.class::cast)
                        .map(Tool::name)
                        .collect(Collectors.toSet());

        assertEquals(53, actual.size());
        assertEquals(EXPECTED_TOOL_NAMES, actual);
    }

    @Test
    void bareHtmlElementsAreResolvedAsCssSelectors() {
        assertTrue(BrowserTargetResolver.looksLikeSelector("body"));
        assertTrue(BrowserTargetResolver.looksLikeSelector("pre"));
        assertTrue(BrowserTargetResolver.looksLikeSelector("my-weather-card"));
        assertTrue(BrowserTargetResolver.looksLikeSelector("#forecast"));
        assertFalse(BrowserTargetResolver.looksLikeSelector("上海天气"));
    }

    @Test
    void operationGateSerializesAccessAcrossVirtualThreads() throws Exception {
        BrowserOperationGate gate = new BrowserOperationGate();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first =
                    executor.submit(
                            () -> {
                                gate.enter();
                                try {
                                    firstEntered.countDown();
                                    await(releaseFirst);
                                } finally {
                                    gate.exit();
                                }
                            });
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

            Future<?> second =
                    executor.submit(
                            () -> {
                                secondAttempted.countDown();
                                gate.enter();
                                try {
                                    secondEntered.countDown();
                                } finally {
                                    gate.exit();
                                }
                            });

            try {
                assertTrue(secondAttempted.await(1, TimeUnit.SECONDS));
                assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
            } finally {
                releaseFirst.countDown();
            }

            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test task interrupted", interrupted);
        }
    }
}
