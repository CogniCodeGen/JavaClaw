package com.javaclaw.server.browser;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.server.network.NetworkGrantService;
import com.javaclaw.server.persistence.H2BrowserSiteRepository;
import com.javaclaw.server.persistence.H2NetworkGrantRepository;
import com.javaclaw.server.persistence.H2Persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserSiteServiceTest {
    @TempDir
    Path temporary;

    @Test
    void configurationAndSecretsAreVersionBoundWithoutPretendingThatAnUnconfiguredBrowserCanRun() throws Exception {
        try (var persistence = new H2Persistence(temporary.resolve("data"))) {
            var workspace = persistence
                    .workspaces()
                    .create("sites", Files.createDirectory(temporary.resolve("workspace")), "workspace");
            var secretStore = persistence.secretStore(temporary.resolve("configuration"));
            try (var service = new BrowserSiteService(
                    new H2BrowserSiteRepository(persistence.database()),
                    persistence.workspaces(),
                    persistence.attachments(),
                    persistence.journal(),
                    secretStore,
                    null,
                    new NetworkGrantService(
                            new H2NetworkGrantRepository(persistence.database()), persistence.workspaces()))) {
                var draft = new BrowserSite(
                        null,
                        workspace.id().value(),
                        "Knowledge",
                        URI.create("https://example.test"),
                        Set.of(URI.create("https://static.example.test")),
                        true,
                        0,
                        null);
                assertThrows(IllegalArgumentException.class, () -> service.put(draft, 0, false, "no-confirm"));
                var site = service.put(draft, 0, true, "site");
                assertEquals(site, service.put(draft, 0, true, "site"));
                assertEquals(2, site.allowedOrigins().size());
                char[] secret = "site-private-password".toCharArray();
                try {
                    service.putSecret(site.id(), "password", secret, "credential");
                } finally {
                    java.util.Arrays.fill(secret, '\0');
                }
                assertTrue(service.secret(site.id(), "password").isPresent());
                assertFalse(service.list(workspace.id().value()).toString().contains("site-private-password"));
                var changed = service.put(
                        new BrowserSite(
                                site.id(),
                                site.workspaceId(),
                                "Changed",
                                site.origin(),
                                site.allowedOrigins(),
                                true,
                                site.revision(),
                                null),
                        site.revision(),
                        true,
                        "change");
                assertEquals(2, changed.revision());
                assertTrue(
                        service.secret(site.id(), "password").isEmpty(),
                        "old site credentials must not cross a permission revision");
                assertThrows(
                        IllegalStateException.class,
                        () -> service.startLogin(site.id(), changed.revision(), true, "login"));
                assertThrows(
                        IllegalStateException.class,
                        () -> service.startLogin(site.id(), changed.revision(), true, "login"));
                service.disable(site.id(), changed.revision(), "disabled");
                assertFalse(service.list(site.workspaceId()).getFirst().enabled());
            }
        }
    }
}
