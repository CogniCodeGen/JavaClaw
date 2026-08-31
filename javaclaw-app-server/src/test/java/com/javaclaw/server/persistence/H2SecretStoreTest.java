package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2SecretStoreTest {
    @TempDir
    Path temporary;

    @Test
    void encryptsOutsideDatabaseKeyRotatesAndEnforcesRevision() throws Exception {
        Path configuration = temporary.resolve("configuration");
        try (H2Persistence threads = new H2Persistence(temporary.resolve("data-v4"))) {
            H2SecretStore secrets = threads.secretStore(configuration);
            char[] original = "not-in-the-database".toCharArray();
            var first = secrets.put("provider", "openai", original, "put-1");
            assertEquals(1, first.revision());
            assertArrayEquals(original, secrets.resolve("provider", "openai").orElseThrow());

            var replay = secrets.put("provider", "openai", original, "put-1");
            assertEquals(first, replay);
            assertThrows(
                    IllegalStateException.class,
                    () -> secrets.put("provider", "openai", "different-secret".toCharArray(), "put-1"));
            assertArrayEquals(original, secrets.resolve("provider", "openai").orElseThrow());

            byte[] cipherText = threads.database().query(connection -> {
                try (var query = connection.prepareStatement("""
                        SELECT cipher_text FROM credentials
                        WHERE namespace = 'provider' AND secret_name = 'openai'
                        """);
                        var row = query.executeQuery()) {
                    assertTrue(row.next());
                    return row.getBytes(1);
                }
            });
            assertFalse(Arrays.equals("not-in-the-database".getBytes(StandardCharsets.UTF_8), cipherText));

            assertEquals(2, secrets.rotateMasterKey());
            assertArrayEquals(original, secrets.resolve("provider", "openai").orElseThrow());
            long revision = secrets.metadata("provider", "openai").orElseThrow().revision();
            assertTrue(revision > first.revision());
            assertTrue(secrets.remove("provider", "openai", revision, "remove-1"));
            assertTrue(secrets.remove("provider", "openai", revision, "remove-1"));
            assertThrows(
                    IllegalStateException.class, () -> secrets.remove("provider", "openai", revision + 1, "remove-1"));
            assertTrue(secrets.resolve("provider", "openai").isEmpty());
        }

        Path keyFile = configuration.resolve("credential-master-keys.bin");
        assertTrue(Files.isRegularFile(keyFile));
        if (Files.getFileStore(keyFile).supportsFileAttributeView("posix")) {
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(keyFile));
        }
    }
}
