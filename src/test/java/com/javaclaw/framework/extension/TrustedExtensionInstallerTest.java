package com.javaclaw.framework.extension;

import com.javaclaw.framework.spi.ExtensionArtifactRecord;
import com.javaclaw.framework.spi.ExtensionArtifactRepository;
import com.javaclaw.framework.spi.SemanticVersion;
import com.javaclaw.extensionfixture.FixtureAgentExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustedExtensionInstallerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void previewOnlyHashesAndDoesNotTryToLoadJarCode() throws Exception {
        Path invalidJar = temporaryDirectory.resolve("extension.jar");
        Files.writeString(invalidJar, "not a jar");
        TrustedExtensionInstaller installer = installer();

        TrustedExtensionInstaller.InstallPreview preview = installer.preview(invalidJar);

        assertEquals(invalidJar.toAbsolutePath(), preview.path());
        assertEquals(64, preview.sha256().length());
        assertEquals(Files.size(invalidJar), preview.sizeBytes());
    }

    @Test
    void installRejectsAnArtifactChangedAfterApprovalBeforeLoadingIt() throws Exception {
        Path invalidJar = temporaryDirectory.resolve("changed.jar");
        Files.writeString(invalidJar, "first bytes");
        TrustedExtensionInstaller installer = installer();
        String approvedHash = installer.preview(invalidJar).sha256();
        Files.writeString(invalidJar, "different bytes");

        assertThrows(SecurityException.class,
                () -> installer.install(invalidJar, approvedHash, true));
    }

    @Test
    void denialDoesNotInspectOrCacheTheJar() throws Exception {
        Path invalidJar = temporaryDirectory.resolve("denied.jar");
        Files.writeString(invalidJar, "not a jar");
        TrustedExtensionInstaller installer = installer();

        assertThrows(SecurityException.class,
                () -> installer.install(invalidJar, "0".repeat(64), false));
        try (var cachedFiles = Files.list(temporaryDirectory.resolve("cache"))) {
            assertEquals(List.of(), cachedFiles.toList());
        }
    }

    @Test
    void installsPublishesAndReloadsARealServiceProviderJar() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        Files.createDirectories(temporaryDirectory.resolve("cache"));
        TrustedExtensionInstaller installer = new TrustedExtensionInstaller(
                temporaryDirectory.resolve("cache"), repository, Clock.systemUTC());
        Path jar = createFixtureJar();

        TrustedExtensionInstaller.InstallPreview preview = installer.preview(jar);
        assertTrue(repository.records.isEmpty(), "preview must not authorize or load providers");
        List<ExtensionArtifact> installed = installer.install(jar, preview.sha256(), true);

        assertEquals(List.of("fixture.agent"), installed.stream()
                .map(value -> value.extension().descriptor().id()).toList());
        assertEquals(1, repository.records.size());
        assertEquals(preview.sha256(), repository.records.getFirst().artifactSha256());
        assertTrue(Files.isRegularFile(repository.records.getFirst().artifactPath()));

        try (ExtensionManager manager = ExtensionManagerTestManager.create()) {
            ExtensionRegistrySnapshot snapshot = manager.publish(installed);
            assertTrue(snapshot.resolve("fixture.agent", "=1.0.0").isPresent());
        }

        TrustedExtensionInstaller.LoadResult reloaded = installer.loadAuthorized();
        try {
            assertTrue(reloaded.failures().isEmpty(), reloaded.failures().toString());
            assertEquals(List.of("fixture.agent"), reloaded.artifacts().stream()
                    .map(value -> value.extension().descriptor().id()).toList());
        } finally {
            ExtensionArtifact.closeClassLoaders(reloaded.artifacts());
        }
    }

    private Path createFixtureJar() throws Exception {
        Path jar = temporaryDirectory.resolve("fixture-extension.jar");
        String classResource = FixtureAgentExtension.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(classResource));
            try (var input = FixtureAgentExtension.class.getClassLoader()
                    .getResourceAsStream(classResource)) {
                if (input == null) throw new IllegalStateException("fixture class bytes not found");
                input.transferTo(output);
            }
            output.closeEntry();
            output.putNextEntry(new JarEntry(
                    "META-INF/services/com.javaclaw.framework.spi.AgentFrameworkExtension"));
            output.write((FixtureAgentExtension.class.getName() + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private TrustedExtensionInstaller installer() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("cache"));
        return new TrustedExtensionInstaller(temporaryDirectory.resolve("cache"),
                new EmptyRepository(), Clock.systemUTC());
    }

    private static final class EmptyRepository implements ExtensionArtifactRepository {
        @Override public void authorize(ExtensionArtifactRecord artifact) {
            throw new AssertionError("authorization was not expected");
        }
        @Override public void authorizeAll(List<ExtensionArtifactRecord> artifacts) {
            throw new AssertionError("authorization was not expected");
        }
        @Override public List<ExtensionArtifactRecord> authorizedArtifacts() { return List.of(); }
        @Override public int setEnabledForNewRuns(String extensionId, boolean enabled) { return 0; }
        @Override public boolean revoke(
                String extensionId, SemanticVersion version, String artifactSha256) { return false; }
        @Override public void revokeAll(List<ExtensionArtifactRecord> artifacts) { }
    }

    private static final class RecordingRepository implements ExtensionArtifactRepository {
        private final List<ExtensionArtifactRecord> records = new ArrayList<>();

        @Override public void authorize(ExtensionArtifactRecord artifact) {
            authorizeAll(List.of(artifact));
        }
        @Override public void authorizeAll(List<ExtensionArtifactRecord> artifacts) {
            records.addAll(artifacts);
        }
        @Override public List<ExtensionArtifactRecord> authorizedArtifacts() {
            return List.copyOf(records);
        }
        @Override public int setEnabledForNewRuns(String extensionId, boolean enabled) {
            return 0;
        }
        @Override public boolean revoke(
                String extensionId, SemanticVersion version, String artifactSha256) {
            return records.removeIf(value -> value.extensionId().equals(extensionId)
                    && value.version().equals(version)
                    && value.artifactSha256().equals(artifactSha256));
        }
        @Override public void revokeAll(List<ExtensionArtifactRecord> artifacts) {
            records.removeAll(artifacts);
        }
    }
}
