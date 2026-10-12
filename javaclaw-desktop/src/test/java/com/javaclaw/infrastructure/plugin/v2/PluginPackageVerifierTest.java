package com.javaclaw.infrastructure.plugin.v2;

import com.javaclaw.application.plugin.v2.PluginManagementService.PluginException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.SSLContext;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;

import static org.junit.jupiter.api.Assertions.*;

class PluginPackageVerifierTest {
    @TempDir Path temporary;

    @Test void verifiesWholeInventoryAndKeepsPreviewStatic() throws Exception {
        Path root = temporary.toRealPath();
        var verifier = verifier(root);
        Path archive = PluginFixture.archive(root, "1.0", new byte[] {1, 2, 3});
        var preview = verifier.inspect(archive, event -> { }, () -> false).preview();
        assertEquals("fixture", preview.id()); assertTrue(preview.signatureVerified());
        assertEquals(2, preview.files().size()); assertEquals(64, preview.signerFingerprint().length());
        assertEquals("fixture", PluginFixture.JSON.readTree(preview.manifestJson()).path("id").asText());
        // 无效 JAR 仍可静态预览，证明预览阶段没有执行入口类。
        assertFalse(preview.previewId().isBlank());
    }

    @Test void rejectsTamperedMissingAndUnsignedPayloads() throws Exception {
        Path root = temporary.toRealPath(); var verifier = verifier(root);
        Map<String, byte[]> payload = Map.of("plugin.json", PluginFixture.manifest("1.0").getBytes(StandardCharsets.UTF_8), "plugin.jar", new byte[] {1});
        for (String variant : new String[] {"tampered", "missing", "extra", "signature"}) {
            Map<String, byte[]> signed = new LinkedHashMap<>(PluginFixture.signed(payload));
            switch (variant) {
                case "tampered" -> signed.put("plugin.jar", new byte[] {9});
                case "missing" -> signed.remove("plugin.jar");
                case "extra" -> signed.put("extra.txt", new byte[] {1});
                case "signature" -> signed.put("signature.json", "{}".getBytes(StandardCharsets.UTF_8));
            }
            Path zip = PluginFixture.zip(root.resolve(variant + ".zip"), signed);
            assertThrows(PluginException.class, () -> verifier.inspect(zip, event -> { }, () -> false), variant);
        }
    }

    @Test void rejectsTraversalCaseAliasesAndCancelsWithoutLeavingStaging() throws Exception {
        Path root = temporary.toRealPath(); var verifier = verifier(root);
        for (Map<String, byte[]> entries : java.util.List.of(Map.of("../escape.txt", new byte[] {1}), Map.of("Plugin.jar", new byte[] {1}, "plugin.jar", new byte[] {2}))) {
            Path zip = PluginFixture.zip(root.resolve(java.util.UUID.randomUUID() + ".zip"), entries);
            assertThrows(PluginException.class, () -> verifier.inspect(zip, event -> { }, () -> false));
        }
        Path zip = PluginFixture.archive(root, "1.0", new byte[] {1});
        assertThrows(CancellationException.class, () -> verifier.inspect(zip, event -> { }, () -> true));
        try (var staging = Files.list(root.resolve("staging"))) { assertEquals(0, staging.count()); }
        assertFalse(Files.exists(root.getParent().resolve("escape.txt")));
    }

    @Test void rejectsNonHttpsSourcesBeforeNetworkAccess() throws Exception {
        var verifier = verifier(temporary.toRealPath());
        for (String source : java.util.List.of("http://example.com/a.zip", "file:///tmp/a.zip", "https://name:secret@example.com/a.zip"))
            assertThrows(PluginException.class, () -> verifier.inspect(URI.create(source), event -> { }, () -> false));
    }

    @Test void previewsUnsignedPackagesWithoutPromotingSignatureTrust() throws Exception {
        Path root = temporary.toRealPath(); var verifier = verifier(root);
        Map<String, byte[]> contents = new LinkedHashMap<>(PluginFixture.signed(Map.of("plugin.json", PluginFixture.manifest("1.0").getBytes(StandardCharsets.UTF_8), "plugin.jar", new byte[] {1})));
        contents.remove("signature.json");
        var preview = verifier.inspect(PluginFixture.zip(root.resolve("unsigned.zip"), contents), event -> { }, () -> false).preview();
        assertFalse(preview.signatureVerified()); assertEquals("", preview.signerFingerprint()); assertEquals(64, preview.artifactHash().length());
        contents.put("plugin.jar", new byte[] {9});
        assertThrows(PluginException.class, () -> verifier.inspect(PluginFixture.zip(root.resolve("unsigned-tampered.zip"), contents), event -> { }, () -> false));
    }

    @Test void closesStalledHttpsBodyAtDeadlineAndRemovesPartialStaging() throws Exception {
        Path root = temporary.toRealPath(); SSLContext tls = tls(root);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        CountDownLatch release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var http = HttpClient.newBuilder().sslContext(tls).build()) {
            server.setExecutor(workers);
            server.createContext("/stalled.zip", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            });
            server.start();
            try {
                var verifier = new PluginPackageVerifier(root.resolve("staging"), PluginFixture.JSON, http, Duration.ofMillis(150));
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    var failure = assertThrows(PluginException.class, () -> verifier.inspect(URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/stalled.zip"), event -> { }, () -> false));
                    assertEquals("download_failed", failure.code());
                });
                try (var staged = Files.list(root.resolve("staging"))) { assertEquals(0, staged.count()); }
            } finally { release.countDown(); server.stop(0); }
        }
    }

    private SSLContext tls(Path root) throws Exception {
        Path storeFile = root.resolve("https-fixture.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process generator = new ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1",
                "-storetype", "PKCS12", "-keystore", storeFile.toString(), "-storepass", "fixture-password", "-keypass", "fixture-password",
                "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "1", "-noprompt").redirectErrorStream(true).start();
        try {
            assertTrue(generator.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, generator.exitValue(), new String(generator.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally { if (generator.isAlive()) generator.destroyForcibly(); }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storeFile)) { store.load(input, "fixture-password".toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, "fixture-password".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), trust.getTrustManagers(), null); return tls;
    }

    private PluginPackageVerifier verifier(Path root) throws Exception { return new PluginPackageVerifier(root.resolve("staging"), PluginFixture.JSON, HttpClient.newHttpClient()); }
}
