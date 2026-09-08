package com.javaclaw.desktop.settings;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopPresenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkConfigurationEventsTest {
    @Test
    void 仅成功写回执发布失效并原样保留回执和失败原因() throws Exception {
        try (DesktopPresenter desktop = new DesktopPresenter(ignored -> {
            throw new IOException("未连接");
        }, Runnable::run, Clock.systemUTC())) {
            SdkCoreSettingsGateway gateway = new SdkCoreSettingsGateway(desktop);
            List<DesktopConfigurationChange> events = new ArrayList<>();
            gateway.onConfigurationChanged(events::add);
            CompletableFuture<String> request = new CompletableFuture<>();
            var result = gateway.changed(request, DesktopConfigurationChange.Kind.PROVIDERS).toCompletableFuture();
            assertTrue(events.isEmpty());
            request.complete("权威回执");
            assertEquals("权威回执", result.join());
            assertEquals(1, events.size());
            assertEquals(DesktopConfigurationChange.Kind.PROVIDERS, events.getFirst().kind());

            IllegalStateException failure = new IllegalStateException("保存失败");
            var failed = gateway.changed(CompletableFuture.failedFuture(failure),
                    DesktopConfigurationChange.Kind.PROVIDERS).toCompletableFuture();
            assertSame(failure, assertThrows(CompletionException.class, failed::join).getCause());
            assertEquals(1, events.size());
            assertThrows(CompletionException.class, () -> gateway.providers().toCompletableFuture().join());
            assertEquals(1, events.size());
        }
    }
}
