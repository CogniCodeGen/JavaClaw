package com.javaclaw.mcp;

import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.config.FileDatabaseAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigManagerPersistenceTest {

    @Test
    void plaintextRowsAreMigratedAndWorkspaceSnapshotPreventsCrossWorkspaceWrites(@TempDir Path dir)
            throws Exception {
        DatabaseAccess database = new FileDatabaseAccess(dir);
        try (var c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO mcp_servers(
                         workspace_id, name, command, args_json, env_json, url, headers_json, enabled)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, "ws-a");
            ps.setString(2, "remote");
            ps.setString(3, null);
            ps.setString(4, "[\"--token\",\"arg-secret\"]");
            ps.setString(5, "{\"INTERNAL\":\"env-secret\"}");
            ps.setString(6, "https://93.184.216.34/mcp?token=url-secret");
            ps.setString(7, "{\"Authorization\":\"Bearer header-secret\"}");
            ps.setBoolean(8, false);
            ps.executeUpdate();
        }

        AtomicReference<String> workspace = new AtomicReference<>("ws-a");
        McpConfigManager manager = new McpConfigManager(
                database, workspace::get,
                McpConfigManagerPersistenceTest::encrypt,
                McpConfigManagerPersistenceTest::decrypt);

        McpServerConfig loaded = manager.getServer("remote");
        assertEquals("arg-secret", loaded.getArgs().get(1));
        assertEquals("env-secret", loaded.getEnv().get("INTERNAL"));
        assertEquals("Bearer header-secret", loaded.getHeaders().get("Authorization"));

        try (var c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT args_json, env_json, url, headers_json
                     FROM mcp_servers WHERE workspace_id = 'ws-a' AND name = 'remote'
                     """);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            for (int i = 1; i <= 4; i++) {
                String stored = rs.getString(i);
                assertTrue(stored.startsWith("ENC("), stored);
                assertFalse(stored.contains("secret"), stored);
            }
        }

        workspace.set("ws-b");
        assertThrows(IllegalStateException.class,
                () -> manager.putServerChecked(new McpServerConfig(
                        "other", "https://93.184.216.34/mcp", Map.of(), false)));

        manager.reload();
        manager.putServerChecked(new McpServerConfig(
                "other", "https://93.184.216.34/mcp", Map.of(), false));
        assertEquals(List.of("other"), manager.getAllServers().stream()
                .map(McpServerConfig::getName).toList());
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
