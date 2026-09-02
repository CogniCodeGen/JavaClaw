package com.javaclaw.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.client.transport.StdioProcessTransport;
import com.javaclaw.client.transport.UnixDomainSocketTransport;
import com.javaclaw.nativehost.transport.WindowsNamedPipeTransport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopLaunchOptionsTest {
    private static final String SOCKET = "javaclaw.server.socket";
    private static final String PIPE = "javaclaw.server.pipe";
    private static final String EXECUTABLE = "javaclaw.server.executable";
    private static final String STARTUP_TIMEOUT = "javaclaw.server.startup-timeout";

    @TempDir
    Path temporaryDirectory;

    private String originalSocket;
    private String originalPipe;
    private String originalExecutable;
    private String originalStartupTimeout;

    @BeforeEach
    void rememberProperties() {
        originalSocket = System.getProperty(SOCKET);
        originalPipe = System.getProperty(PIPE);
        originalExecutable = System.getProperty(EXECUTABLE);
        originalStartupTimeout = System.getProperty(STARTUP_TIMEOUT);
        System.clearProperty(SOCKET);
        System.clearProperty(PIPE);
        System.clearProperty(EXECUTABLE);
        System.clearProperty(STARTUP_TIMEOUT);
    }

    @AfterEach
    void restoreProperties() {
        restore(SOCKET, originalSocket);
        restore(PIPE, originalPipe);
        restore(EXECUTABLE, originalExecutable);
        restore(STARTUP_TIMEOUT, originalStartupTimeout);
    }

    @Test
    void requiresExactlyOneAbsoluteTransportProperty() {
        assertThrows(IllegalStateException.class, DesktopLaunchOptions::fromSystemProperties);
        System.setProperty(SOCKET, temporaryDirectory.resolve("app.sock").toString());
        System.setProperty(EXECUTABLE, temporaryDirectory.resolve("server").toString());
        assertThrows(IllegalStateException.class, DesktopLaunchOptions::fromSystemProperties);

        System.clearProperty(EXECUTABLE);
        System.setProperty(PIPE, "javaclaw-app");
        assertThrows(IllegalStateException.class, DesktopLaunchOptions::fromSystemProperties);

        System.clearProperty(PIPE);
        System.setProperty(SOCKET, "relative.sock");
        assertThrows(IllegalStateException.class, DesktopLaunchOptions::fromSystemProperties);

        System.clearProperty(SOCKET);
        System.setProperty(EXECUTABLE, temporaryDirectory.resolve("missing").toString());
        assertThrows(IllegalStateException.class, DesktopLaunchOptions::fromSystemProperties);

        System.clearProperty(EXECUTABLE);
        System.setProperty(PIPE, "bad\\pipe");
        assertThrows(IllegalArgumentException.class, DesktopLaunchOptions::fromSystemProperties);
    }

    @Test
    void buildsOnlyUdsOrDirectStdioTransport() throws IOException {
        System.setProperty(SOCKET, "  " + temporaryDirectory.resolve("server.sock") + "  ");
        DesktopLaunchOptions socket = DesktopLaunchOptions.fromSystemProperties();
        assertInstanceOf(UnixDomainSocketTransport.class, socket.transport());
        assertEquals(com.javaclaw.protocol.TransportKind.UDS, socket.transport().kind());
        assertEquals(Duration.ZERO, socket.startupTimeout());

        System.clearProperty(SOCKET);
        System.setProperty(PIPE, "javaclaw-desktop-test");
        DesktopLaunchOptions pipe = DesktopLaunchOptions.fromSystemProperties();
        assertInstanceOf(WindowsNamedPipeTransport.class, pipe.transport());
        assertEquals(
                com.javaclaw.protocol.TransportKind.NAMED_PIPE, pipe.transport().kind());

        System.clearProperty(PIPE);
        Path executable = Files.createFile(temporaryDirectory.resolve("javaclaw-server"));
        System.setProperty(EXECUTABLE, executable.toString());
        DesktopLaunchOptions stdio = DesktopLaunchOptions.fromSystemProperties();
        assertInstanceOf(StdioProcessTransport.class, stdio.transport());
        assertEquals(
                com.javaclaw.protocol.TransportKind.STDIO, stdio.transport().kind());
    }

    @Test
    void connectorDelegatesToConfiguredTransportWithoutFallback() {
        IOException expected = new IOException("offline");
        DesktopLaunchOptions options = new DesktopLaunchOptions(
                new com.javaclaw.protocol.LocalTransport() {
                    @Override
                    public com.javaclaw.protocol.TransportKind kind() {
                        return com.javaclaw.protocol.TransportKind.STDIO;
                    }

                    @Override
                    public com.javaclaw.protocol.RpcConnection connect() throws IOException {
                        throw expected;
                    }
                },
                Duration.ZERO);

        assertEquals(
                expected,
                assertThrows(IOException.class, () -> options.connector().connect(ignored -> {})));
        assertThrows(NullPointerException.class, () -> new DesktopLaunchOptions(null, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new DesktopLaunchOptions(options.transport(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DesktopLaunchOptions(options.transport(), Duration.ofSeconds(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DesktopLaunchOptions(options.transport(), Duration.ofSeconds(61)));
    }

    @Test
    void parsesOnlyBoundedIsoStartupTimeout() {
        System.setProperty(SOCKET, temporaryDirectory.resolve("server.sock").toString());
        System.setProperty(STARTUP_TIMEOUT, " PT15S ");
        assertEquals(
                Duration.ofSeconds(15),
                DesktopLaunchOptions.fromSystemProperties().startupTimeout());

        System.setProperty(STARTUP_TIMEOUT, "not-a-duration");
        assertThrows(IllegalStateException.class, DesktopLaunchOptions::fromSystemProperties);
        System.setProperty(STARTUP_TIMEOUT, "-PT1S");
        assertThrows(IllegalArgumentException.class, DesktopLaunchOptions::fromSystemProperties);
        System.setProperty(STARTUP_TIMEOUT, "PT61S");
        assertThrows(IllegalArgumentException.class, DesktopLaunchOptions::fromSystemProperties);
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
