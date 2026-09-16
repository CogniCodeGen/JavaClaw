package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BrowserOriginConfirmationsTest {
    @TempDir
    Path temporary;

    @Test
    void 恢复授权等待复用原预览和输入身份且不延长用户确认期限() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(temporary)) {
            var invocation = fixture.modelInvocation(fixture.turn(), "browser_open", Map.of());
            var origin = URI.create("https://new.example.com");
            var original = new BrowserOriginConfirmations(fixture.host, fixture.grants).get(invocation, origin);
            var host = fixture.host;
            var later = new SiteBrowserHostContext(
                    host.database(),
                    host.core(),
                    host.accounts(),
                    host.attachments(),
                    host.permissions(),
                    host.providers(),
                    host.inputs(),
                    host.json(),
                    Clock.offset(host.clock(), Duration.ofSeconds(30)));
            var recovered = new BrowserOriginConfirmations(later, fixture.grants).get(invocation, origin);
            assertEquals(original, recovered);
            assertEquals(original.preview().expiresAt(), recovered.request().expiresAt());
            assertNotEquals(
                    original.request().id(),
                    new BrowserOriginConfirmations(host, fixture.grants)
                            .get(invocation, URI.create("https://another.example.com"))
                            .request()
                            .id());
        }
    }
}
