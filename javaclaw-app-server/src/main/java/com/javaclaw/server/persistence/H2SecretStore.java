package com.javaclaw.server.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.javaclaw.core.api.ThreadId;
import com.javaclaw.server.security.SecretStore;

/** AES-256-GCM credential store whose versioned master-key ring never enters H2. */
public final class H2SecretStore implements SecretStore {
    private static final int MAGIC = 0x4a434b34; // JCK4
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.copyOf(EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    private static final Set<PosixFilePermission> OWNER_FILE =
            Set.copyOf(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

    private final H2Database database;
    private final Path keyDirectory;
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();
    private final Object keyGate = new Object();
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();

    /** 绑定共享数据库并创建/验证外置 AES 主密钥环；POSIX/ACL 无法保证 owner-only 时拒绝继续。 */
    public H2SecretStore(H2Database database, Path platformConfigurationDirectory) {
        this.database = Objects.requireNonNull(database, "database");
        this.keyDirectory = initializeDirectory(platformConfigurationDirectory);
        this.keyFile = keyDirectory.resolve("credential-master-keys.bin");
        synchronized (keyGate) {
            if (Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                verifyOwnerOnly(keyFile, false);
                readKeyRing();
            } else {
                byte[] key = new byte[KEY_BYTES];
                random.nextBytes(key);
                try {
                    writeKeyRing(new KeyRing(1, Map.of(1, key)));
                } finally {
                    Arrays.fill(key, (byte) 0);
                }
            }
        }
    }

    @Override
    public SecretMetadata put(String namespace, String name, char[] value, String idempotencyKey) {
        namespace = required(namespace, "namespace", 160);
        name = required(name, "name", 500);
        char[] secret = Objects.requireNonNull(value, "value").clone();
        if (secret.length == 0) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        String key = normalizeKey(idempotencyKey);
        String finalNamespace = namespace;
        String finalName = name;
        try {
            KeyRing ring;
            synchronized (keyGate) {
                ring = readKeyRing();
            }
            byte[] plaintext = encode(secret);
            try {
                return database.transaction(connection -> {
                    CredentialRow existing =
                            find(connection, finalNamespace, finalName, true).orElse(null);
                    if (existing != null && key != null && key.equals(existing.idempotencyKey())) {
                        byte[] previousKey = ring.keys().get(Math.toIntExact(existing.keyRevision()));
                        if (previousKey == null) {
                            throw new IllegalStateException("credential master key is unavailable");
                        }
                        byte[] previous =
                                decrypt(previousKey, existing.nonce(), existing.cipherText(), aad(existing.id()));
                        try {
                            if (!MessageDigest.isEqual(previous, plaintext)) {
                                throw new IllegalStateException("idempotency key was already used with another secret");
                            }
                            return existing.metadata();
                        } finally {
                            Arrays.fill(previous, (byte) 0);
                        }
                    }
                    long revision = existing == null ? 1 : existing.revision() + 1;
                    String id = credentialId(finalNamespace, finalName);
                    Encrypted encrypted = encrypt(ring.currentKey(), plaintext, aad(id));
                    long now = System.currentTimeMillis();
                    if (existing == null) {
                        try (PreparedStatement insert = connection.prepareStatement("""
                                INSERT INTO credentials(
                                    credential_id, namespace, secret_name, purpose,
                                    cipher_text, nonce, key_revision, revision,
                                    idempotency_key, created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """)) {
                            insert.setString(1, id);
                            insert.setString(2, finalNamespace);
                            insert.setString(3, finalName);
                            insert.setString(4, finalNamespace);
                            insert.setBytes(5, encrypted.cipherText());
                            insert.setBytes(6, encrypted.nonce());
                            insert.setLong(7, ring.currentRevision());
                            insert.setLong(8, revision);
                            insert.setString(9, key);
                            insert.setLong(10, now);
                            insert.setLong(11, now);
                            insert.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement update = connection.prepareStatement("""
                                UPDATE credentials SET cipher_text = ?, nonce = ?,
                                    key_revision = ?, revision = ?, idempotency_key = ?,
                                    updated_at = ? WHERE credential_id = ? AND revision = ?
                                """)) {
                            update.setBytes(1, encrypted.cipherText());
                            update.setBytes(2, encrypted.nonce());
                            update.setLong(3, ring.currentRevision());
                            update.setLong(4, revision);
                            update.setString(5, key);
                            update.setLong(6, now);
                            update.setString(7, existing.id());
                            update.setLong(8, existing.revision());
                            if (update.executeUpdate() != 1) {
                                throw new IllegalStateException("credential revision conflict");
                            }
                        }
                    }
                    return new SecretMetadata(finalNamespace, finalName, true, revision, Instant.ofEpochMilli(now));
                });
            } finally {
                Arrays.fill(plaintext, (byte) 0);
                ring.destroy();
            }
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    @Override
    public Optional<char[]> resolve(String namespace, String name) {
        String finalNamespace = required(namespace, "namespace", 160);
        String finalName = required(name, "name", 500);
        Optional<CredentialRow> row = database.query(connection -> find(connection, finalNamespace, finalName, false));
        if (row.isEmpty()) {
            return Optional.empty();
        }
        KeyRing ring;
        synchronized (keyGate) {
            ring = readKeyRing();
        }
        try {
            byte[] key = ring.keys().get(Math.toIntExact(row.get().keyRevision()));
            if (key == null) {
                throw new IllegalStateException("credential master key is unavailable");
            }
            byte[] plaintext = decrypt(
                    key,
                    row.get().nonce(),
                    row.get().cipherText(),
                    aad(row.get().id()));
            try {
                return Optional.of(decode(plaintext));
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } finally {
            ring.destroy();
        }
    }

    @Override
    public Optional<SecretMetadata> metadata(String namespace, String name) {
        String finalNamespace = required(namespace, "namespace", 160);
        String finalName = required(name, "name", 500);
        return database.query(
                connection -> find(connection, finalNamespace, finalName, false).map(CredentialRow::metadata));
    }

    @Override
    public boolean remove(String namespace, String name, long expectedRevision, String idempotencyKey) {
        namespace = required(namespace, "namespace", 160);
        name = required(name, "name", 500);
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        String finalNamespace = namespace;
        String finalName = name;
        return database.transaction(connection -> {
            String requestHash = H2IdempotencyStore.requestHash(finalNamespace, finalName, expectedRevision);
            Optional<Boolean> replay =
                    idempotency.replay(connection, "credential/remove", idempotencyKey, requestHash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM credentials
                    WHERE namespace = ? AND secret_name = ? AND revision = ?
                    """)) {
                delete.setString(1, finalNamespace);
                delete.setString(2, finalName);
                delete.setLong(3, expectedRevision);
                int count = delete.executeUpdate();
                if (count == 0
                        && find(connection, finalNamespace, finalName, false).isPresent()) {
                    throw new IllegalStateException("credential revision conflict");
                }
                boolean removed = count == 1;
                idempotency.record(
                        connection,
                        "credential/remove",
                        idempotencyKey,
                        requestHash,
                        removed,
                        System.currentTimeMillis());
                return removed;
            }
        });
    }

    @Override
    public int rotateMasterKey() {
        synchronized (keyGate) {
            KeyRing old = readKeyRing();
            int nextRevision = Math.addExact(old.currentRevision(), 1);
            byte[] next = new byte[KEY_BYTES];
            random.nextBytes(next);
            LinkedHashMap<Integer, byte[]> keys = new LinkedHashMap<>(old.copyKeys());
            keys.put(nextRevision, next.clone());
            KeyRing replacement = new KeyRing(nextRevision, keys);
            try {
                // Persist the new key before using it. A failed DB transaction leaves a harmless
                // extra key, never ciphertext whose key was lost.
                writeKeyRing(replacement);
                database.transaction(connection -> {
                    try (PreparedStatement query = connection.prepareStatement("""
                            SELECT * FROM credentials FOR UPDATE
                            """);
                            ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            CredentialRow row = read(rows);
                            byte[] previous = old.keys().get(Math.toIntExact(row.keyRevision()));
                            if (previous == null) {
                                throw new IllegalStateException("credential master key is unavailable");
                            }
                            byte[] plaintext = decrypt(previous, row.nonce(), row.cipherText(), aad(row.id()));
                            try {
                                Encrypted encrypted = encrypt(next, plaintext, aad(row.id()));
                                try (PreparedStatement update = connection.prepareStatement("""
                                        UPDATE credentials SET cipher_text = ?, nonce = ?,
                                            key_revision = ?, revision = revision + 1,
                                            updated_at = ? WHERE credential_id = ?
                                        """)) {
                                    update.setBytes(1, encrypted.cipherText());
                                    update.setBytes(2, encrypted.nonce());
                                    update.setInt(3, nextRevision);
                                    update.setLong(4, System.currentTimeMillis());
                                    update.setString(5, row.id());
                                    update.executeUpdate();
                                }
                            } finally {
                                Arrays.fill(plaintext, (byte) 0);
                            }
                        }
                    }
                    return null;
                });
                return nextRevision;
            } finally {
                Arrays.fill(next, (byte) 0);
                old.destroy();
                replacement.destroy();
            }
        }
    }

    private Optional<CredentialRow> find(java.sql.Connection connection, String namespace, String name, boolean lock)
            throws java.sql.SQLException {
        String sql = "SELECT * FROM credentials WHERE namespace = ? AND secret_name = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, namespace);
            query.setString(2, name);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    private static CredentialRow read(ResultSet row) throws java.sql.SQLException {
        return new CredentialRow(
                row.getString("credential_id"),
                row.getString("namespace"),
                row.getString("secret_name"),
                row.getBytes("cipher_text"),
                row.getBytes("nonce"),
                row.getLong("key_revision"),
                row.getLong("revision"),
                row.getString("idempotency_key"),
                row.getLong("updated_at"));
    }

    private Encrypted encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return new Encrypted(cipher.doFinal(plaintext), nonce);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("cannot encrypt credential", failure);
        }
    }

    private static byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("credential authentication failed", failure);
        }
    }

    private KeyRing readKeyRing() {
        verifyOwnerOnly(keyFile, false);
        try (DataInputStream input = new DataInputStream(Files.newInputStream(keyFile))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("invalid key-ring magic");
            }
            int current = input.readInt();
            int count = input.readInt();
            if (current < 1 || count < 1 || count > 1_024) {
                throw new IOException("invalid key-ring header");
            }
            LinkedHashMap<Integer, byte[]> keys = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                int revision = input.readInt();
                byte[] key = input.readNBytes(KEY_BYTES);
                if (revision < 1 || key.length != KEY_BYTES || keys.put(revision, key) != null) {
                    throw new IOException("invalid key-ring entry");
                }
            }
            if (input.read() >= 0 || !keys.containsKey(current)) {
                throw new IOException("invalid key-ring trailer");
            }
            return new KeyRing(current, keys);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read credential master-key ring", failure);
        }
    }

    private void writeKeyRing(KeyRing ring) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(keyDirectory, ".credential-keys-", ".tmp");
            setOwnerOnly(temporary, false);
            try (DataOutputStream output = new DataOutputStream(
                    Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
                output.writeInt(MAGIC);
                output.writeInt(ring.currentRevision());
                output.writeInt(ring.keys().size());
                for (Map.Entry<Integer, byte[]> entry : ring.keys().entrySet()) {
                    output.writeInt(entry.getKey());
                    output.write(entry.getValue());
                }
            }
            try {
                Files.move(temporary, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("atomic master-key replacement is unavailable", failure);
            }
            setOwnerOnly(keyFile, false);
            temporary = null;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot persist credential master-key ring", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static Path initializeDirectory(Path value) {
        Path directory = Objects.requireNonNull(value, "platformConfigurationDirectory")
                .toAbsolutePath()
                .normalize();
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("credential key path is not a directory");
            }
            Files.createDirectories(directory);
            directory = directory.toRealPath();
            setOwnerOnly(directory, true);
            verifyOwnerOnly(directory, true);
            return directory;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot secure credential key directory", failure);
        }
    }

    private static void setOwnerOnly(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix =
                Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(directory ? OWNER_DIRECTORY : OWNER_FILE);
            return;
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("filesystem exposes neither POSIX permissions nor ACLs");
        }
        AclEntry owner = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(
                        directory
                                ? EnumSet.of(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
                                : EnumSet.noneOf(AclEntryFlag.class))
                .build();
        acl.setAcl(java.util.List.of(owner));
    }

    private static void verifyOwnerOnly(Path path, boolean directory) {
        try {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("symbolic links are forbidden");
            }
            PosixFileAttributeView posix =
                    Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                Set<PosixFilePermission> expected = directory ? OWNER_DIRECTORY : OWNER_FILE;
                if (!expected.equals(posix.readAttributes().permissions())) {
                    throw new IOException("path is accessible by another user: " + path);
                }
                return;
            }
            AclFileAttributeView acl =
                    Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            java.nio.file.attribute.UserPrincipal owner = acl == null ? null : acl.getOwner();
            if (acl == null
                    || acl.getAcl().stream()
                            .anyMatch(
                                    entry -> !entry.principal().equals(owner) && entry.type() == AclEntryType.ALLOW)) {
                throw new IOException("path ACL is not owner-exclusive: " + path);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("credential master-key permissions are unsafe", failure);
        }
    }

    private static byte[] encode(char[] value) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return result;
    }

    private static char[] decode(byte[] value) {
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(value));
        char[] result = new char[decoded.remaining()];
        decoded.get(result);
        if (decoded.hasArray()) {
            Arrays.fill(decoded.array(), '\0');
        }
        return result;
    }

    private static byte[] aad(String id) {
        return id.getBytes(StandardCharsets.UTF_8);
    }

    private static String credentialId(String namespace, String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(namespace.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            return "cred_" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String required(String value, String name, int maximum) {
        String normalized = ThreadId.required(value, name);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " characters");
        }
        return normalized;
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("idempotencyKey exceeds 500 characters");
        }
        return normalized;
    }

    private record Encrypted(byte[] cipherText, byte[] nonce) {}

    private record CredentialRow(
            String id,
            String namespace,
            String name,
            byte[] cipherText,
            byte[] nonce,
            long keyRevision,
            long revision,
            String idempotencyKey,
            long updatedAt) {
        private SecretMetadata metadata() {
            return new SecretMetadata(namespace, name, true, revision, Instant.ofEpochMilli(updatedAt));
        }
    }

    private record KeyRing(int currentRevision, Map<Integer, byte[]> keys) {
        private KeyRing {
            keys = Map.copyOf(keys);
        }

        private byte[] currentKey() {
            byte[] key = keys.get(currentRevision);
            if (key == null) {
                throw new NoSuchElementException("current master key is missing");
            }
            return key;
        }

        private Map<Integer, byte[]> copyKeys() {
            LinkedHashMap<Integer, byte[]> result = new LinkedHashMap<>();
            keys.forEach((revision, key) -> result.put(revision, key.clone()));
            return result;
        }

        private void destroy() {
            keys.values().forEach(key -> Arrays.fill(key, (byte) 0));
        }
    }
}
