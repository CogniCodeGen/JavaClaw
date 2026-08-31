package com.javaclaw.server.extension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginBundleInstallerTest {
    @TempDir
    Path temporary;

    @Test
    void previewsVerifiedBundleHashAndPermissionsWithoutRegisteringOrInstalling() throws Exception {
        var catalog = new PluginCatalog(new PluginBundleLoader(PluginSignatureVerifier.REJECT_UNTRUSTED));
        var installer = new PluginBundleInstaller(
                temporary.resolve("plugins"), temporary.resolve("staging"), temporary.resolve("Trash"), catalog);
        byte[] zip = archive(validEntries());
        String sha256 = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(zip));
        var preview = installer.preview(new ByteArrayInputStream(zip), sha256);
        assertEquals("sample.plugin", preview.id());
        assertEquals(sha256, preview.sha256());
        assertFalse(preview.signatureVerified());
        assertFalse(preview.requiresPermissions());
        assertFalse(catalog.find(preview.id()).isPresent());
        assertFalse(Files.exists(temporary.resolve("plugins/sample.plugin")));
        try (var staged = Files.list(temporary.resolve("staging"))) {
            assertEquals(0, staged.count());
        }
        assertThrows(IOException.class, () -> installer.preview(new ByteArrayInputStream(zip), "0".repeat(64)));
    }

    @Test
    void requiresUnsignedConfirmationInstallsAtomicallyAndMovesUninstallToTrash() throws Exception {
        PluginCatalog catalog = new PluginCatalog(new PluginBundleLoader(PluginSignatureVerifier.REJECT_UNTRUSTED));
        PluginBundleInstaller installer = new PluginBundleInstaller(
                temporary.resolve("plugins"), temporary.resolve("staging"), temporary.resolve("Trash"), catalog);
        byte[] zip = archive(validEntries());

        assertThrows(IOException.class, () -> installer.install(new ByteArrayInputStream(zip), null, false));
        var installed = installer.install(new ByteArrayInputStream(zip), null, true);
        assertEquals("sample.plugin", installed.plugin().manifest().id());
        assertTrue(installed
                .plugin()
                .bundleRoot()
                .startsWith(temporary.resolve("plugins").toRealPath()));
        assertEquals(64, installed.bundleSha256().length());

        Path trash = installer.uninstall("sample.plugin");
        assertTrue(Files.isDirectory(trash));
        assertFalse(catalog.find("sample.plugin").isPresent());
        assertFalse(Files.exists(installed.plugin().bundleRoot()));
    }

    @Test
    void rejectsTraversalAndExtremeCompressionBeforeExtraction() throws Exception {
        PluginBundleInstaller installer = new PluginBundleInstaller(
                temporary.resolve("plugins"),
                temporary.resolve("staging"),
                temporary.resolve("Trash"),
                new PluginCatalog(new PluginBundleLoader(PluginSignatureVerifier.REJECT_UNTRUSTED)));
        Map<String, byte[]> traversal = new LinkedHashMap<>(validEntries());
        traversal.put("../outside", "bad".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                IOException.class, () -> installer.install(new ByteArrayInputStream(archive(traversal)), null, true));

        Map<String, byte[]> bomb = new LinkedHashMap<>(validEntries());
        bomb.put("large.txt", new byte[2 * 1024 * 1024]);
        assertThrows(IOException.class, () -> installer.install(new ByteArrayInputStream(archive(bomb)), null, true));
        assertFalse(Files.exists(temporary.resolve("outside")));
    }

    private static Map<String, byte[]> validEntries() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(".javaclaw-plugin/plugin.json", """
                {"apiVersion":4,"minimumProtocolVersion":1,
                 "id":"sample.plugin","version":"1.0.0","name":"Sample",
                 "processes":[{"id":"service","kind":"SERVICE",
                   "entrypoint":"bin/service","arguments":[],
                   "workspaceRead":false,"workspaceWrite":false,
                   "networkAllowlist":[],"timeoutMillis":1000,
                   "outputLimitBytes":4096,"healthCheckMethod":"health"}],
                 "skills":[]}
                """.getBytes(StandardCharsets.UTF_8));
        entries.put("bin/service", "service".getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static byte[] archive(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
