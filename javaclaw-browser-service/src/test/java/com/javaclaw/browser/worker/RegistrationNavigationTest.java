package com.javaclaw.browser.worker;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationNavigationTest {
    @Test
    void 初始导航被未授权跳转来源阻断后保持窗口等待明确授权() throws Exception {
        var browser = new InteractivePlaywrightFixture();
        var denied = URI.create("https://www.example.net");
        AtomicBoolean aborted = new AtomicBoolean();
        browser.createdPage = page -> page.navigation = () -> {
            browser.route.accept(deniedRoute(denied, aborted));
            page.uri = "chrome-error://chromewebdata/";
            throw new PlaywrightException("net::ERR_BLOCKED_BY_CLIENT");
        };
        try (PipedInputStream input = new PipedInputStream();
                PipedOutputStream host = new PipedOutputStream(input);
                var connection =
                        new InteractiveWorkerConnection(input, new ByteArrayOutputStream(), new CanonicalJson());
                var actor = new RegistrationBrowserActor(
                        RegistrationBrowserActorTest.task(Instant.now().plusSeconds(60)),
                        connection,
                        () -> browser.playwright)) {
            var state = actor.open();
            assertEquals(SiteRegistrationContracts.State.ACTIVE, state.state());
            assertEquals(Set.of(denied), state.access().pendingOrigins());
            assertTrue(state.page().uri().isEmpty());
            assertTrue(aborted.get());
            assertFalse(browser.pages.getFirst().closed);
            assertEquals(0, browser.contextCloses);
        }
        assertEquals(1, browser.contextCloses);
    }

    @Test
    void 无待授权来源的启动异常继续失败且不能伪造可用窗口() {
        var browser = new InteractivePlaywrightFixture();
        browser.createdPage = page -> page.navigation = () -> {
            throw new PlaywrightException("net::ERR_BLOCKED_BY_CLIENT");
        };
        try (var actor = new RegistrationBrowserActor(
                RegistrationBrowserActorTest.task(Instant.now().plusSeconds(60)), null, () -> browser.playwright)) {
            assertThrows(PlaywrightException.class, actor::open);
        }
        assertEquals(1, browser.contextCloses);
    }

    private static Route deniedRoute(URI origin, AtomicBoolean aborted) {
        Request request = InteractivePlaywrightFixture.proxy(Request.class, (target, method, args) -> {
            return method.getName().equals("url") ? origin + "/login" : null;
        });
        return InteractivePlaywrightFixture.proxy(Route.class, (target, method, args) -> {
            if (method.getName().equals("request")) {
                return request;
            }
            if (method.getName().equals("abort")) {
                aborted.set(true);
            }
            return null;
        });
    }
}
