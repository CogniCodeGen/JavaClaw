package com.javaclaw.site;

import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.config.FileDatabaseAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteCredentialManagerBehaviorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void matchingPrefersExactThenLongestWildcardAndSupportsMultipleAccounts() {
        DatabaseAccess database = database("matching");
        SiteCredentialManager manager = manager(database, new AtomicReference<>("workspace"));
        SiteCredential broad = manager.putChecked(credential("broad", " *.example.com "));
        SiteCredential narrowA = manager.putChecked(credential("narrow-a", "*.login.example.com"));
        SiteCredential narrowB = manager.putChecked(credential("narrow-b", "*.login.example.com"));
        SiteCredential exactA = manager.putChecked(credential("exact-a", "EXAMPLE.COM"));
        SiteCredential exactB = manager.putChecked(credential("exact-b", " example.com "));
        manager.putChecked(credential("empty", " "));

        assertEquals(List.of(exactA.getId(), exactB.getId()), manager.findAllByUrl(
                "https://EXAMPLE.com/path").stream().map(SiteCredential::getId).toList());
        assertEquals(List.of(narrowA.getId(), narrowB.getId()), manager.findAllByUrl(
                "deep.login.example.com/path").stream().map(SiteCredential::getId).toList());
        assertEquals(broad.getId(), manager.findByUrl("https://api.example.com").getId());
        assertNull(manager.findByUrl("https://unrelated.test"));
        assertTrue(manager.findAllByUrl(null).isEmpty());
        assertTrue(manager.findAllByUrl(" ").isEmpty());
        assertTrue(manager.findAllByUrl("not a valid url [").isEmpty());
        assertFalse(SiteCredentialManager.wildcardMatches(null, "example.com"));
        assertFalse(SiteCredentialManager.wildcardMatches("a.example.com", null));
    }

    @Test
    void credentialCrudUsesDefensiveCopiesAndPersistsOnlyValidatedMetadata() throws Exception {
        DatabaseAccess database = database("crud");
        AtomicReference<String> workspace = new AtomicReference<>("workspace");
        SiteCredentialManager manager = manager(database, workspace);

        assertTrue(manager.getConfigFilePath().contains("javaclaw"));
        assertThrows(NullPointerException.class, () -> manager.putChecked(null));
        SiteCredential source = credential("account", "example.com");
        source.setId(" ");
        source.setCreatedAt(0);
        source.setPassword("plain-secret");
        SiteCredential saved = manager.put(source);
        assertNotNull(saved.getId());
        assertFalse(saved.getId().isBlank());
        assertTrue(saved.getCreatedAt() > 0);
        assertNotEquals(saved.getId(), source.getId());
        source.setName("mutated outside");
        assertEquals("account", manager.get(saved.getId()).getName());

        SiteCredential update = copy(saved);
        update.setName("updated");
        manager.putChecked(update);
        assertEquals("updated", manager.get(saved.getId()).getName());
        assertEquals(1, manager.all().size());
        manager.save();

        SiteCredentialManager reloaded = manager(database, workspace);
        assertEquals("updated", reloaded.get(saved.getId()).getName());
        assertEquals("plain-secret", reloaded.get(saved.getId()).getPassword());

        SiteCredential metadataSecret = credential("safe", "example.com");
        metadataSecret.setNotes("api_key=live-secret-value");
        assertThrows(IllegalArgumentException.class,
                () -> manager.putChecked(metadataSecret));
        SiteCredential preEncrypted = credential("safe", "example.com");
        preEncrypted.setPassword("ENC(not-plaintext)");
        assertThrows(IllegalStateException.class, () -> manager.putChecked(preEncrypted));

        try (var connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO site_credentials(
                         workspace_id, id, name, host_pattern, created_at,
                         last_used_at, has_session)
                     VALUES ('workspace', 'orphan', 'orphan', 'orphan.test', 1, 0, FALSE)
                     """)) {
            statement.executeUpdate();
        }
        manager.save();
        try (var connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM site_credentials
                     WHERE workspace_id = 'workspace' AND id = 'orphan'
                     """);
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }

        assertFalse(manager.removeChecked("missing"));
        assertTrue(manager.removeChecked(saved.getId()));
        assertNull(manager.get(saved.getId()));
        manager.remove("missing");
    }

    @Test
    void sessionsAndBindingsAreTransactionalAndRejectInvalidRelationships() {
        DatabaseAccess database = database("sessions");
        SiteCredentialManager manager = manager(database, new AtomicReference<>("workspace"));
        SiteCredential exact = manager.putChecked(credential("exact", "example.com"));
        SiteCredential other = manager.putChecked(credential("other", "other.test"));

        assertThrows(NullPointerException.class,
                () -> manager.saveSessionChecked(null, "{}", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> manager.saveSessionChecked(exact, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> manager.saveSessionChecked(exact, " ", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> manager.saveSessionChecked(exact,
                        "x".repeat(16 * 1024 * 1024 + 1), null, null));

        assertFalse(manager.tryWriteSession(null, "{}"));
        assertFalse(manager.tryWriteSession(exact.getId(), null));
        assertFalse(manager.tryWriteSession("missing", "{}"));
        manager.writeSession("missing", "{}");
        assertTrue(manager.tryWriteSession(exact.getId(), "{\"cookies\":[]}"));
        assertTrue(manager.get(exact.getId()).isHasSession());
        assertEquals("{\"cookies\":[]}", manager.readSession(exact.getId()));

        assertFalse(manager.bindAccount("scope", "https://example.com", null));
        assertFalse(manager.bindAccount("scope", "https://example.com", "missing"));
        assertFalse(manager.bindAccount("scope", "https://example.com", other.getId()));
        assertFalse(manager.bindAccount(null, "https://example.com", exact.getId()));
        assertFalse(manager.bindAccount("scope", "bad url [", exact.getId()));
        assertTrue(manager.bindAccount("scope", "https://example.com/home", exact.getId()));
        assertEquals(exact.getId(), manager.readAccountBinding(
                "scope", "https://example.com/home"));
        assertEquals(exact.getId(), manager.findBoundByUrl(
                "scope", "https://example.com/home").getId());

        SiteCredential noLongerMatches = copy(exact);
        noLongerMatches.setHostPattern("moved.test");
        manager.putChecked(noLongerMatches);
        assertNull(manager.findBoundByUrl("scope", "https://example.com/home"));
        assertNull(manager.readAccountBinding("scope", "https://example.com/home"));

        assertFalse(manager.bindNewAccount(null, "https://example.com"));
        assertFalse(manager.bindNewAccount("scope", "bad url ["));
        assertTrue(manager.bindNewAccount("scope", "https://example.com"));
        assertTrue(manager.isNewAccountBound("scope", "https://example.com"));
        assertNull(manager.findBoundByUrl("scope", "https://example.com"));
        manager.clearAccountBinding(null, "https://example.com");
        manager.clearAccountBinding("scope", "bad url [");
        manager.clearAccountBinding("scope", "https://example.com");
        assertFalse(manager.isNewAccountBound("scope", "https://example.com"));

        assertTrue(manager.bindNewAccount("scope-a", "https://example.com"));
        assertTrue(manager.bindNewAccount("scope-b", "https://example.com"));
        manager.clearScopeBindings(null);
        manager.clearScopeBindings(" ");
        manager.clearScopeBindings("scope-a");
        assertNull(manager.readAccountBinding("scope-a", "https://example.com"));
        assertNotNull(manager.readAccountBinding("scope-b", "https://example.com"));

        manager.touchUsage("missing");
        long before = manager.get(exact.getId()).getLastUsedAt();
        manager.touchUsage(exact.getId());
        assertTrue(manager.get(exact.getId()).getLastUsedAt() >= before);
        manager.clearSession("missing");
        manager.clearSession(exact.getId());
        assertFalse(manager.get(exact.getId()).isHasSession());
        assertNull(manager.readSession(exact.getId()));
    }

    @Test
    void sessionSaveCanAtomicallyBindAndRemovalClearsEveryDependentRow() {
        DatabaseAccess database = database("atomic-session");
        SiteCredentialManager manager = manager(database, new AtomicReference<>("workspace"));
        SiteCredential credential = credential("account", "example.com");

        SiteCredential saved = manager.saveSessionChecked(
                credential, "{\"origins\":[]}", "scope", "https://example.com/home");
        assertTrue(saved.isHasSession());
        assertTrue(saved.getLastUsedAt() > 0);
        assertEquals(saved.getId(), manager.readAccountBinding(
                "scope", "https://example.com/home"));
        assertEquals("{\"origins\":[]}", manager.readSession(saved.getId()));
        assertTrue(manager.removeChecked(saved.getId()));
        assertNull(manager.readSession(saved.getId()));
        assertNull(manager.readAccountBinding("scope", "https://example.com/home"));

        SiteCredential withoutBinding = manager.saveSessionChecked(
                credential("no-bind", "example.com"), "{}", " ", "bad url [");
        assertTrue(withoutBinding.isHasSession());
    }

    @Test
    void encryptionAndDatabaseFailuresNeverPublishUnconfirmedState() throws Exception {
        DatabaseAccess database = database("failures");
        AtomicReference<String> workspace = new AtomicReference<>("workspace");
        SiteCredentialManager invalidEncryption = new SiteCredentialManager(
                database, workspace::get, value -> value, SiteCredentialManagerBehaviorTest::decrypt);
        SiteCredential secret = credential("secret", "example.com");
        secret.setPassword("plain-secret");
        assertThrows(IllegalStateException.class,
                () -> invalidEncryption.putChecked(secret));
        assertTrue(invalidEncryption.all().isEmpty());

        try (var connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO site_credentials(
                         workspace_id, id, name, host_pattern, password_enc,
                         created_at, last_used_at, has_session)
                     VALUES ('encrypted-workspace', 'bad', 'bad', 'example.com',
                             'ENC(YmFk)', 1, 0, FALSE)
                     """)) {
            statement.executeUpdate();
        }
        assertThrows(IllegalStateException.class, () -> new SiteCredentialManager(
                database, () -> "encrypted-workspace",
                SiteCredentialManagerBehaviorTest::encrypt, value -> value));

        AtomicBoolean fail = new AtomicBoolean();
        DatabaseAccess flaky = new DatabaseAccess() {
            @Override
            public java.sql.Connection open() throws SQLException {
                if (fail.get()) throw new SQLException("database unavailable");
                return database.open();
            }

            @Override
            public String description() {
                return "flaky-database";
            }
        };
        SiteCredentialManager manager = manager(flaky, workspace);
        SiteCredential saved = manager.putChecked(credential("stable", "example.com"));
        fail.set(true);

        assertThrows(IllegalStateException.class,
                () -> manager.putChecked(credential("new", "example.com")));
        assertEquals(1, manager.all().size());
        assertThrows(IllegalStateException.class, manager::save);
        assertThrows(IllegalStateException.class,
                () -> manager.removeChecked(saved.getId()));
        assertFalse(manager.tryWriteSession(saved.getId(), "{}"));
        assertNull(manager.readSession(saved.getId()));
        assertNull(manager.readAccountBinding("scope", "https://example.com"));
        assertFalse(manager.bindNewAccount("scope", "https://example.com"));
        manager.clearAccountBinding("scope", "https://example.com");
        manager.clearScopeBindings("scope");
        assertThrows(IllegalStateException.class,
                () -> manager.clearSession(saved.getId()));

        workspace.set("other-workspace");
        assertThrows(IllegalStateException.class,
                () -> manager.putChecked(credential("stale", "example.com")));
        assertFalse(manager.bindNewAccount("scope", "https://example.com"));
    }

    private DatabaseAccess database(String name) {
        return new FileDatabaseAccess(temporaryDirectory.resolve(name));
    }

    private static SiteCredentialManager manager(
            DatabaseAccess database, AtomicReference<String> workspace) {
        return new SiteCredentialManager(
                database, workspace::get,
                SiteCredentialManagerBehaviorTest::encrypt,
                SiteCredentialManagerBehaviorTest::decrypt);
    }

    private static SiteCredential credential(String name, String hostPattern) {
        return new SiteCredential(null, name, hostPattern,
                "https://" + hostPattern.strip().replace("*.", "") + "/login",
                "user@example.com", null, "notes");
    }

    private static SiteCredential copy(SiteCredential source) {
        SiteCredential result = new SiteCredential();
        result.setId(source.getId());
        result.setName(source.getName());
        result.setHostPattern(source.getHostPattern());
        result.setLoginUrl(source.getLoginUrl());
        result.setUsername(source.getUsername());
        result.setPassword(source.getPassword());
        result.setNotes(source.getNotes());
        result.setCreatedAt(source.getCreatedAt());
        result.setLastUsedAt(source.getLastUsedAt());
        result.setHasSession(source.isHasSession());
        return result;
    }

    private static String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return plain;
        return "ENC(" + Base64.getEncoder().encodeToString(
                plain.getBytes(StandardCharsets.UTF_8)) + ")";
    }

    private static String decrypt(String encrypted) {
        if (encrypted == null || !encrypted.startsWith("ENC(") || !encrypted.endsWith(")")) {
            return encrypted;
        }
        return new String(Base64.getDecoder().decode(
                encrypted.substring(4, encrypted.length() - 1)), StandardCharsets.UTF_8);
    }
}
