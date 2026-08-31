package com.javaclaw.server.network;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.server.persistence.H2NetworkGrantRepository;
import com.javaclaw.server.persistence.H2Persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkGrantServiceTest {
    @TempDir
    Path temporary;

    @Test
    void canonicalizesIpv6WithoutChangingTheExactPortOrAcceptingZones() {
        assertEquals(
                URI.create("https://[fd00:0:0:0:0:0:0:1]:8443"),
                NetworkGrantService.origin(URI.create("https://[fd00::1]:8443/page")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkGrantService.origin(URI.create("https://[fe80::1%25en0]:443")));
        assertThrows(
                IllegalArgumentException.class, () -> NetworkGrantService.origin(URI.create("https://example.test:0")));
    }

    @Test
    void exactWorkspacePurposeOriginAndAddressMustAllMatchAndRevocationIsImmediate() throws Exception {
        try (var persistence = new H2Persistence(temporary.resolve("data"))) {
            var workspace = persistence
                    .workspaces()
                    .create("private endpoint", Files.createDirectory(temporary.resolve("workspace")), "workspace");
            var service = new NetworkGrantService(
                    new H2NetworkGrantRepository(persistence.database()), persistence.workspaces());
            var requested = new NetworkGrant(
                    null,
                    workspace.id().value(),
                    "BROWSER",
                    URI.create("https://intranet.example:8443"),
                    Set.of("10.20.30.40"),
                    Instant.now().plusSeconds(600),
                    true,
                    0,
                    null);
            assertThrows(
                    IllegalArgumentException.class, () -> service.put(requested, 0, false, "missing-confirmation"));
            var grant = service.put(requested, 0, true, "grant");
            assertEquals(grant, service.put(requested, 0, true, "grant"));
            var ip = InetAddress.getByName("10.20.30.40");
            assertTrue(service.permits(
                    workspace.id().value(), "BROWSER", URI.create("https://intranet.example:8443/page"), ip));
            assertFalse(service.permits(workspace.id().value(), "MCP", grant.origin(), ip));
            assertFalse(
                    service.permits(workspace.id().value(), "BROWSER", URI.create("https://intranet.example:443"), ip));
            assertFalse(service.permits(
                    workspace.id().value(), "BROWSER", grant.origin(), InetAddress.getByName("10.20.30.41")));
            assertThrows(IllegalStateException.class, () -> service.disable(grant.id(), 9, "stale"));
            service.disable(grant.id(), grant.revision(), "revoke");
            assertFalse(service.permits(workspace.id().value(), "BROWSER", grant.origin(), ip));
            for (String forbidden : Set.of(
                    "127.0.0.1", "169.254.169.254", "100.100.100.200", "168.63.129.16", "0.0.0.0", "224.1.1.1")) {
                var denied = new NetworkGrant(
                        null,
                        workspace.id().value(),
                        "BROWSER",
                        URI.create("http://private.test"),
                        Set.of(forbidden),
                        Instant.now().plusSeconds(60),
                        true,
                        0,
                        null);
                assertThrows(IllegalArgumentException.class, () -> service.put(denied, 0, true, "deny-" + forbidden));
            }
        }
    }
}
