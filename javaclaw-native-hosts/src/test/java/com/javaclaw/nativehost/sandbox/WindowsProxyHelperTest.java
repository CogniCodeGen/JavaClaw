package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowsProxyHelperTest {
    @TempDir
    Path directory;

    @Test
    void versionedProxyFieldsNeverBecomeTargetArguments() {
        var request = WindowsSandboxHelperArguments.parse(arguments("1234", "--"));

        assertEquals(List.of("worker.exe", "literal --network-proxy-v1"), request.arguments());
        assertEquals(
                SandboxNetworkAccess.Mode.PROXY_ONLY, request.networkAccess().mode());
        assertEquals(
                "127.0.0.1",
                request.networkAccess().proxyEndpoint().orElseThrow().getHostString());
        assertEquals(1234, request.networkAccess().proxyEndpoint().orElseThrow().getPort());
        assertInstanceOf(WindowsProxyCleanup.class, request.networkAccess().closeTunnels());
        assertThrows(IllegalArgumentException.class, () -> WindowsSandboxHelperArguments.parse(arguments("0", "--")));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowsSandboxHelperArguments.parse(arguments("1234", "missing-delimiter")));
    }

    @Test
    void cleanupRequiresParentAcknowledgementAndOnlyCreatesOneRequest() throws Exception {
        Files.createFile(directory.resolve(WindowsProxyCleanup.ACKNOWLEDGED));
        WindowsProxyCleanup cleanup = new WindowsProxyCleanup(directory);

        cleanup.run();
        cleanup.run();

        try (var files = Files.list(directory)) {
            assertEquals(
                    List.of("close-ack", "close-request"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    private String[] arguments(String port, String delimiter) {
        return new String[] {
            directory.toString(),
            "false",
            "1500",
            "4096",
            "2048",
            "2",
            "8",
            "0",
            "0",
            "--network-proxy-v1",
            "test-grant",
            port,
            directory.toString(),
            delimiter,
            "worker.exe",
            "literal --network-proxy-v1"
        };
    }
}
