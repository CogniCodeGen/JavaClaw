package com.javaclaw.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.javaclaw.nativehost.credential.MasterKeyProtector;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AppServerStdioLifecycleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @ResourceLock("system-standard-streams")
    void 单连接Stdio在客户端Eof后立即退出而不等待后台IdleDelay() {
        var previousInput = System.in;
        var previousOutput = System.out;
        System.setIn(new ByteArrayInputStream(new byte[0]));
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                Clock clock = Clock.fixed(Instant.parse("2026-09-02T05:00:00Z"), ZoneOffset.UTC);
                AppServerBootstrap.Foundation foundation = PlatformFoundationFactory.create(
                        temporaryDirectory.resolve("stdio/data-v6"), clock, new MemoryProtector(), required -> {});
                try (AppServerBootstrap.Components components =
                        ConfiguredProviderBootstrap.create(foundation, reference -> Optional.empty())) {
                    AppServerMain.serveStdio(components);
                }
            });
        } finally {
            System.setIn(previousInput);
            System.setOut(previousOutput);
        }
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String keyId) {
            byte[] value = keys.get(keyId);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public void store(String keyId, byte[] key) {
            keys.put(keyId, key.clone());
        }

        @Override
        public void delete(String keyId) {
            keys.remove(keyId);
        }
    }
}
