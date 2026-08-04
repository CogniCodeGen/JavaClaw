package com.javaclaw.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectAccessPolicyTest {

    @Test
    void relativePathResolvesInsideProject() {
        Path resolved = ProjectAccessPolicy.resolveProjectPath("src/main");

        assertEquals(ProjectAccessPolicy.projectRoot().resolve("src/main").normalize(), resolved);
    }

    @Test
    void absoluteOutsidePathAndTraversalAreRejected(@TempDir Path outside) {
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.resolveProjectPath(outside.toString()));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.resolveProjectPath("../outside.txt"));
    }

    @Test
    void symlinkEscapeIsRejected(@TempDir Path outside) throws Exception {
        Path link = ProjectAccessPolicy.projectRoot().resolve("target/policy-test-link");
        Files.createDirectories(link.getParent());
        try {
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, outside);
            assertThrows(SecurityException.class,
                    () -> ProjectAccessPolicy.resolveProjectPath("target/policy-test-link/secret.txt"));
        } finally {
            Files.deleteIfExists(link);
        }
        assertTrue(ProjectAccessPolicy.isProjectPath(ProjectAccessPolicy.projectRoot()));
    }

    @Test
    void genericFileAccessRejectsManagedDataAndVcsMetadata() {
        Path database = ProjectAccessPolicy.projectRoot().resolve("data/javaclaw.mv.db");
        Path gitConfig = ProjectAccessPolicy.projectRoot().resolve(".git/config");

        assertTrue(ProjectAccessPolicy.isProjectPath(database));
        assertFalse(ProjectAccessPolicy.isProjectFilePath(database));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.resolveProjectPath("data/javaclaw.mv.db"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.resolveProjectPath(".git/config"));
    }

    @Test
    void remoteMcpEndpointRejectsLocalAndLoopbackHosts() {
        assertEquals("93.184.216.34",
                ProjectAccessPolicy.requireRemoteMcpEndpoint("https://93.184.216.34/api").getHost());
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireRemoteMcpEndpoint("http://localhost:8080/mcp"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireRemoteMcpEndpoint("http://127.0.0.1:8080/mcp"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireRemoteMcpEndpoint("http://[::1]:8080/mcp"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireRemoteMcpEndpoint(
                        "http://[0:0:0:0:0:0:0:1]:8080/mcp"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireRemoteMcpEndpoint(
                        "http://localhost.localdomain:8080/mcp"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireRemoteMcpEndpoint("http://192.168.1.20/mcp"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireRemoteMcpEndpoint(
                        "https://user:password@93.184.216.34/mcp"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireRemoteMcpEndpoint(
                        "https://93.184.216.34/mcp?token=hidden-value"));
    }

    @Test
    void endpointSummaryDropsCredentialsQueryAndFragment() {
        assertEquals("https://example.com:8443/mcp",
                ProjectAccessPolicy.remoteEndpointSummary(
                        "https://user:secret@example.com:8443/mcp?token=opaque#part"));
    }

    @Test
    void browserUrlRejectsUnsupportedSchemesAndCredentials() {
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireSafeBrowserUrl("javascript:alert(1)"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireSafeBrowserUrl("file:///etc/passwd"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireSafeBrowserUrl(
                        "https://example.com/callback?token=hidden-value"));
        assertEquals("about:blank", ProjectAccessPolicy.requireSafeBrowserUrl("about:blank"));
    }
}
