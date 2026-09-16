package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserGrantCommandsTest {
    @TempDir
    Path temporary;

    @Test
    void 领域授权列表使用对象契约且精确确认可重放撤销即时生效() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(temporary)) {
            var grants = fixture.json.decode(
                    fixture.service.invoke(fixture.invocation("browser.grants", Map.of(), 0)),
                    BrowserGrantContracts.GrantList.class);
            assertEquals(1, grants.grants().size());
            URI origin = URI.create("https://another.example.com");
            var preview = fixture.json.decode(
                    fixture.service.invoke(fixture.invocation("browser.grant.preview", Map.of("origin", origin), 0)),
                    BrowserGrantContracts.Preview.class);
            assertEquals(origin, preview.origin());
            var confirmation = fixture.invocation("browser.grant.confirm", preview, 0);
            var result = fixture.service.invoke(confirmation);
            var grant = fixture.json.decode(result, BrowserGrantContracts.Grant.class);
            assertEquals(result, fixture.service.invoke(confirmation));
            assertEquals(SecurityGrantState.ACTIVE, grant.state());
            var revoked = fixture.json.decode(
                    fixture.service.invoke(
                            fixture.invocation("browser.grant.revoke", Map.of("id", grant.id()), grant.revision())),
                    BrowserGrantContracts.Grant.class);
            assertEquals(SecurityGrantState.REVOKED, revoked.state());
            assertTrue(fixture.grants.currentOrigins(fixture.workspace, fixture.thread).stream()
                    .noneMatch(origin::equals));
            var model = fixture.modelInvocation(fixture.turn(), "browser.grant.confirm", preview);
            assertThrows(SecurityException.class, () -> fixture.service.invoke(model));
        }
    }
}
