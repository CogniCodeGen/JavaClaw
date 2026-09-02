package com.javaclaw.server;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppServerOptionsTest {
    @Test
    void emptyArgumentsAndExplicitStdioSelectStandardStreams() {
        AppServerOptions implicit = AppServerOptions.parse(new String[0]);
        AppServerOptions explicit = AppServerOptions.parse(new String[] {"--stdio"});

        assertEquals(AppServerOptions.Mode.STDIO, implicit.mode());
        assertEquals(AppServerOptions.StartupProfile.NORMAL, implicit.profile());
        assertNull(implicit.socketPath());
        assertNull(implicit.pipeName());
        assertEquals(implicit, explicit);
    }

    @Test
    void healthCheckSelectsDedicatedStdioOnlyProfile() {
        AppServerOptions options = AppServerOptions.parse(new String[] {"--health-check"});

        assertEquals(AppServerOptions.Mode.STDIO, options.mode());
        assertEquals(AppServerOptions.StartupProfile.HEALTH_CHECK, options.profile());
        assertNull(options.socketPath());
        assertNull(options.pipeName());
    }

    @Test
    void absoluteSocketPathIsNormalized() {
        AppServerOptions options = AppServerOptions.parse(new String[] {"--socket", "/tmp/one/../javaclaw.sock"});

        assertEquals(AppServerOptions.Mode.UNIX_SOCKET, options.mode());
        assertEquals(AppServerOptions.StartupProfile.NORMAL, options.profile());
        assertEquals(Path.of("/tmp/javaclaw.sock"), options.socketPath());
        assertNull(options.pipeName());
    }

    @Test
    void explicitAndDefaultNamedPipeModesUseValidatedLogicalNames() {
        AppServerOptions explicit = AppServerOptions.parse(new String[] {"--pipe", "javaclaw-test_5"});
        AppServerOptions currentUser = AppServerOptions.parse(new String[] {"--pipe-default"});

        assertEquals(AppServerOptions.Mode.NAMED_PIPE, explicit.mode());
        assertEquals(AppServerOptions.StartupProfile.NORMAL, explicit.profile());
        assertEquals("javaclaw-test_5", explicit.pipeName().value());
        assertNull(explicit.socketPath());
        assertEquals(AppServerOptions.Mode.NAMED_PIPE, currentUser.mode());
        assertEquals(com.javaclaw.nativehost.transport.WindowsPipeName.currentUserDefault(), currentUser.pipeName());
    }

    @Test
    void malformedArgumentsAndInconsistentModeAreRejected() {
        assertThrows(NullPointerException.class, () -> AppServerOptions.parse(null));
        assertThrows(IllegalArgumentException.class, () -> AppServerOptions.parse(new String[] {"--socket", "local"}));
        assertThrows(IllegalArgumentException.class, () -> AppServerOptions.parse(new String[] {"--unknown"}));
        assertThrows(IllegalArgumentException.class, () -> AppServerOptions.parse(new String[] {"--pipe", "bad/name"}));
        assertThrows(IllegalArgumentException.class, () -> AppServerOptions.parse(new String[] {"--stdio", "extra"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppServerOptions.parse(new String[] {"--health-check", "--socket"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppServerOptions.parse(new String[] {"--socket", "/tmp/health.sock", "--health-check"}));
        assertThrows(
                NullPointerException.class,
                () -> new AppServerOptions(null, AppServerOptions.StartupProfile.NORMAL, null, null));
        assertThrows(
                NullPointerException.class, () -> new AppServerOptions(AppServerOptions.Mode.STDIO, null, null, null));
        assertThrows(
                NullPointerException.class,
                () -> new AppServerOptions(
                        AppServerOptions.Mode.UNIX_SOCKET, AppServerOptions.StartupProfile.NORMAL, null, null));
        assertThrows(
                NullPointerException.class,
                () -> new AppServerOptions(
                        AppServerOptions.Mode.NAMED_PIPE, AppServerOptions.StartupProfile.NORMAL, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppServerOptions(
                        AppServerOptions.Mode.STDIO,
                        AppServerOptions.StartupProfile.NORMAL,
                        Path.of("/tmp/unexpected.sock"),
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppServerOptions(
                        AppServerOptions.Mode.STDIO,
                        AppServerOptions.StartupProfile.NORMAL,
                        null,
                        com.javaclaw.nativehost.transport.WindowsPipeName.parse("unexpected")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppServerOptions(
                        AppServerOptions.Mode.UNIX_SOCKET,
                        AppServerOptions.StartupProfile.HEALTH_CHECK,
                        Path.of("/tmp/health.sock"),
                        null));
    }
}
