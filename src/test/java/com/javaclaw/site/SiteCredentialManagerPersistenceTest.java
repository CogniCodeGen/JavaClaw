package com.javaclaw.site;

import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.config.FileDatabaseAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteCredentialManagerPersistenceTest {

    @Test
    void sessionSaveEncryptsSensitiveFieldsAndRejectsStaleWorkspace(@TempDir Path dir)
            throws Exception {
        DatabaseAccess database = new FileDatabaseAccess(dir);
        AtomicReference<String> workspace = new AtomicReference<>("ws-a");
        SiteCredentialManager manager = manager(database, workspace);
        SiteCredential credential = new SiteCredential(
                null, "管理后台", "example.com", "https://example.com/login",
                "tester@example.com", "db-password", "仅保存到配置数据库");
        String storageState = "{\"cookies\":[{\"value\":\"cookie-secret\"}]}";

        SiteCredential saved = manager.saveSessionChecked(
                credential, storageState, "chat-1", "https://example.com/home");

        assertEquals(storageState, manager.readSession(saved.getId()));
        try (var c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT c.password_enc, s.storage_state_json,
                            (SELECT COUNT(*) FROM site_account_bindings b
                             WHERE b.workspace_id = c.workspace_id
                               AND b.credential_id = c.id) AS binding_count
                     FROM site_credentials c
                     JOIN site_sessions s ON s.workspace_id = c.workspace_id
                         AND s.credential_id = c.id
                     WHERE c.workspace_id = 'ws-a' AND c.id = ?
                     """)) {
            ps.setString(1, saved.getId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEncryptedWithout(rs.getString(1), "db-password");
                assertEncryptedWithout(rs.getString(2), "cookie-secret");
                assertEquals(1, rs.getInt(3));
            }
        }

        workspace.set("ws-b");
        assertThrows(IllegalStateException.class,
                () -> manager.saveSessionChecked(saved, storageState, null, null));
    }

    @Test
    void plaintextPasswordAndSessionAreMigratedAtomicallyOnLoad(@TempDir Path dir)
            throws Exception {
        DatabaseAccess database = new FileDatabaseAccess(dir);
        try (var c = database.open()) {
            try (PreparedStatement credential = c.prepareStatement("""
                    INSERT INTO site_credentials(
                        workspace_id, id, name, host_pattern, username, password_enc,
                        created_at, last_used_at, has_session)
                    VALUES ('ws-a', 'cred-1', '旧站点', 'example.com', 'tester',
                            'legacy-password', 1, 0, TRUE)
                    """)) {
                credential.executeUpdate();
            }
            try (PreparedStatement session = c.prepareStatement("""
                    INSERT INTO site_sessions(workspace_id, credential_id, storage_state_json)
                    VALUES ('ws-a', 'cred-1', '{"cookies":[{"value":"legacy-cookie"}]}')
                    """)) {
                session.executeUpdate();
            }
        }

        SiteCredentialManager manager = manager(database, new AtomicReference<>("ws-a"));

        assertEquals("legacy-password", manager.get("cred-1").getPassword());
        assertTrue(manager.readSession("cred-1").contains("legacy-cookie"));
        try (var c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT c.password_enc, s.storage_state_json
                     FROM site_credentials c JOIN site_sessions s
                       ON s.workspace_id = c.workspace_id AND s.credential_id = c.id
                     WHERE c.workspace_id = 'ws-a' AND c.id = 'cred-1'
                     """);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEncryptedWithout(rs.getString(1), "legacy-password");
            assertEncryptedWithout(rs.getString(2), "legacy-cookie");
        }
    }

    @Test
    void bindingFailureRollsBackCredentialAndSession(@TempDir Path dir) throws Exception {
        DatabaseAccess database = new FileDatabaseAccess(dir);
        SiteCredentialManager manager = manager(database, new AtomicReference<>("ws-a"));
        SiteCredential credential = new SiteCredential(
                null, "回滚测试", "example.com", null, null, null, null);

        assertThrows(IllegalStateException.class, () -> manager.saveSessionChecked(
                credential, "{\"cookies\":[]}", "s".repeat(600), "https://example.com"));
        assertTrue(manager.all().isEmpty());

        try (var c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT
                       (SELECT COUNT(*) FROM site_credentials WHERE workspace_id = 'ws-a'),
                       (SELECT COUNT(*) FROM site_sessions WHERE workspace_id = 'ws-a'),
                       (SELECT COUNT(*) FROM site_account_bindings WHERE workspace_id = 'ws-a')
                     """);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
            assertEquals(0, rs.getInt(2));
            assertEquals(0, rs.getInt(3));
        }
    }

    private static SiteCredentialManager manager(
            DatabaseAccess database, AtomicReference<String> workspace) {
        return new SiteCredentialManager(
                database, workspace::get,
                SiteCredentialManagerPersistenceTest::encrypt,
                SiteCredentialManagerPersistenceTest::decrypt);
    }

    private static void assertEncryptedWithout(String stored, String secret) {
        assertTrue(stored.startsWith("ENC("), stored);
        assertFalse(stored.contains(secret), stored);
    }

    private static String encrypt(String plain) {
        if (plain == null || plain.isBlank() || plain.startsWith("ENC(")) return plain;
        return "ENC(" + Base64.getEncoder().encodeToString(
                plain.getBytes(StandardCharsets.UTF_8)) + ")";
    }

    private static String decrypt(String encrypted) {
        if (encrypted == null || !encrypted.startsWith("ENC(") || !encrypted.endsWith(")")) {
            return encrypted;
        }
        String value = encrypted.substring(4, encrypted.length() - 1);
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
