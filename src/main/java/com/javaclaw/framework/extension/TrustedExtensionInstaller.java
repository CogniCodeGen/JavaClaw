package com.javaclaw.framework.extension;

import com.javaclaw.framework.spi.AgentFrameworkExtension;
import com.javaclaw.framework.spi.ExtensionArtifactRecord;
import com.javaclaw.framework.spi.ExtensionArtifactRepository;
import com.javaclaw.framework.spi.ExtensionDescriptor;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Installs explicitly trusted host-code extensions and reloads authorized immutable JARs.
 * This is a dependency boundary, not a JVM security sandbox.
 */
public final class TrustedExtensionInstaller {
    private final Path cacheDirectory;
    private final ExtensionArtifactRepository repository;
    private final Clock clock;

    public TrustedExtensionInstaller(
            Path cacheDirectory, ExtensionArtifactRepository repository, Clock clock) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory")
                .toAbsolutePath().normalize();
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Installation must only be called after the UI presents and confirms host-level risk. */
    public List<ExtensionArtifact> install(Path sourceJar, boolean hostPermissionConfirmed) {
        Path source = requireJar(sourceJar);
        return install(source, sha256(source), hostPermissionConfirmed);
    }

    /** Installs only when the artifact still matches the hash that received user approval. */
    public List<ExtensionArtifact> install(
            Path sourceJar, String approvedSha256, boolean hostPermissionConfirmed) {
        if (!hostPermissionConfirmed) {
            throw new SecurityException("system extension requires explicit host-code authorization");
        }
        Path source = requireJar(sourceJar);
        String hash = sha256(source);
        String approvedHash = Objects.requireNonNull(approvedSha256, "approvedSha256")
                .trim().toLowerCase(java.util.Locale.ROOT);
        if (!hash.equals(approvedHash)) {
            throw new SecurityException(
                    "extension artifact changed after host-code authorization");
        }
        Path cached = cacheDirectory.resolve(hash + ".jar");
        copyImmutable(source, cached, hash);
        LoadedJar loaded = load(cached);
        try {
            Map<Coordinate, AgentFrameworkExtension> discovered = index(loaded.extensions());
            List<ExtensionArtifact> artifacts = new ArrayList<>();
            List<ExtensionArtifactRecord> records = new ArrayList<>();
            for (var entry : discovered.entrySet()) {
                Coordinate coordinate = entry.getKey();
                AgentFrameworkExtension extension = entry.getValue();
                records.add(new ExtensionArtifactRecord(
                        coordinate.id(), coordinate.version(), hash, cached,
                        true, true, clock.instant()));
                artifacts.add(new ExtensionArtifact(extension, hash, loaded.classLoader()));
            }
            repository.authorizeAll(records);
            return List.copyOf(artifacts);
        } catch (RuntimeException failure) {
            loaded.close();
            throw failure;
        }
    }

    /** Hash-only inspection. This method never creates a classloader or runs ServiceLoader code. */
    public InstallPreview preview(Path sourceJar) {
        Path source = requireJar(sourceJar);
        try {
            return new InstallPreview(source, sha256(source), Files.size(source));
        } catch (IOException failure) {
            throw new IllegalStateException("cannot inspect extension JAR", failure);
        }
    }

    /** Loads every authorized cache entry; invalid entries are reported and skipped. */
    public LoadResult loadAuthorized() {
        List<ExtensionArtifact> artifacts = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<ArtifactFile, List<ExtensionArtifactRecord>> grouped = new LinkedHashMap<>();
        for (ExtensionArtifactRecord record : repository.authorizedArtifacts()) {
            grouped.computeIfAbsent(new ArtifactFile(record.artifactPath(), record.artifactSha256()),
                    ignored -> new ArrayList<>()).add(record);
        }
        for (var entry : grouped.entrySet()) {
            LoadedJar loaded = null;
            int artifactStart = artifacts.size();
            try {
                Path path = requireJar(entry.getKey().path());
                String actualHash = sha256(path);
                if (!actualHash.equals(entry.getKey().hash())) {
                    throw new SecurityException("authorized JAR hash changed: " + path);
                }
                loaded = load(path);
                Map<Coordinate, AgentFrameworkExtension> discovered = index(loaded.extensions());
                for (ExtensionArtifactRecord record : entry.getValue()) {
                    Coordinate coordinate = new Coordinate(record.extensionId(), record.version());
                    AgentFrameworkExtension extension = discovered.get(coordinate);
                    if (extension == null) {
                        throw new IllegalStateException("authorized extension is absent from JAR: "
                                + coordinate);
                    }
                    artifacts.add(new ExtensionArtifact(extension, actualHash, loaded.classLoader()));
                }
                if (artifacts.size() == artifactStart) loaded.close();
            } catch (RuntimeException failure) {
                while (artifacts.size() > artifactStart) {
                    artifacts.removeLast();
                }
                if (loaded != null) loaded.close();
                failures.add(entry.getKey().path() + ": " + failure.getMessage());
            }
        }
        Map<String, Boolean> enabled = new HashMap<>();
        repository.authorizedArtifacts().forEach(record -> enabled.merge(
                record.extensionId(), record.enabledForNewRuns(), Boolean::logicalOr));
        Set<String> disabled = enabled.entrySet().stream()
                .filter(value -> !value.getValue()).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new LoadResult(artifacts, failures, disabled);
    }

    private LoadedJar load(Path jar) {
        validateJarBoundary(jar);
        SelectiveClassLoader loader;
        try {
            loader = new SelectiveClassLoader(new URL[]{jar.toUri().toURL()},
                    AgentFrameworkExtension.class.getClassLoader());
        } catch (IOException failure) {
            throw new IllegalStateException("cannot create extension classloader", failure);
        }
        try {
            List<AgentFrameworkExtension> extensions = ServiceLoader
                    .load(AgentFrameworkExtension.class, loader).stream()
                    .map(ServiceLoader.Provider::get).toList();
            if (extensions.isEmpty()) {
                throw new IllegalStateException("JAR declares no AgentFrameworkExtension service");
            }
            index(extensions);
            return new LoadedJar(loader, extensions);
        } catch (RuntimeException failure) {
            close(loader);
            throw failure;
        }
    }

    private static void validateJarBoundary(Path jar) {
        List<String> forbiddenReferences = List.of(
                "com/javaclaw/framework/core/",
                "com/javaclaw/framework/springai/",
                "com/javaclaw/framework/extension/",
                "com/javaclaw/framework/store/",
                "com/javaclaw/framework/builtin/");
        try (java.util.jar.JarFile archive = new java.util.jar.JarFile(jar.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                if (entry.getName().startsWith("com/javaclaw/framework/")) {
                    throw new SecurityException(
                            "extension JAR must not package framework classes: " + entry.getName());
                }
                byte[] bytecode;
                try (var input = archive.getInputStream(entry)) {
                    bytecode = input.readAllBytes();
                }
                String constants = new String(bytecode, java.nio.charset.StandardCharsets.ISO_8859_1);
                for (String forbidden : forbiddenReferences) {
                    if (constants.contains(forbidden)) {
                        throw new SecurityException("extension depends on @Internal package "
                                + forbidden.replace('/', '.') + " from " + entry.getName());
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot inspect extension JAR: " + jar, failure);
        }
    }

    private static Map<Coordinate, AgentFrameworkExtension> index(
            List<AgentFrameworkExtension> extensions) {
        Map<Coordinate, AgentFrameworkExtension> indexed = new LinkedHashMap<>();
        for (AgentFrameworkExtension extension : extensions) {
            Coordinate coordinate = Coordinate.of(extension.descriptor());
            if (indexed.putIfAbsent(coordinate, extension) != null) {
                throw new IllegalStateException("duplicate extension service: " + coordinate);
            }
        }
        return indexed;
    }

    private void copyImmutable(Path source, Path target, String expectedHash) {
        try {
            Files.createDirectories(cacheDirectory);
            if (Files.exists(target)) {
                if (!sha256(target).equals(expectedHash)) {
                    throw new SecurityException("extension cache hash mismatch: " + target);
                }
                return;
            }
            Path temporary = Files.createTempFile(cacheDirectory, ".install-", ".jar");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!sha256(temporary).equals(expectedHash)) {
                    throw new SecurityException("extension changed while copying");
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot cache extension JAR", failure);
        }
    }

    private static Path requireJar(Path value) {
        Path path = Objects.requireNonNull(value, "sourceJar").toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) {
            throw new IllegalArgumentException("extension artifact must be a readable JAR: " + path);
        }
        return path;
    }

    private static String sha256(Path path) {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("cannot hash extension JAR: " + path, failure);
        }
    }

    private static void close(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot close extension classloader", failure);
        }
    }

    public record LoadResult(
            List<ExtensionArtifact> artifacts,
            List<String> failures,
            Set<String> disabledExtensionIds) {
        public LoadResult {
            artifacts = List.copyOf(artifacts);
            failures = List.copyOf(failures);
            disabledExtensionIds = Set.copyOf(disabledExtensionIds);
        }
    }

    public record InstallPreview(Path path, String sha256, long sizeBytes) { }

    private record ArtifactFile(Path path, String hash) { }

    private record Coordinate(String id, com.javaclaw.framework.spi.SemanticVersion version) {
        private static Coordinate of(ExtensionDescriptor descriptor) {
            return new Coordinate(descriptor.id(), descriptor.version());
        }
    }

    private record LoadedJar(SelectiveClassLoader classLoader,
                             List<AgentFrameworkExtension> extensions) implements AutoCloseable {
        @Override public void close() { TrustedExtensionInstaller.close(classLoader); }
    }

    /** Parent-first only for host contracts; extension implementation dependencies are child-first. */
    private static final class SelectiveClassLoader extends URLClassLoader {
        private static final List<String> PARENT_FIRST = List.of(
                "java.", "javax.", "jakarta.", "com.javaclaw.framework.api.",
                "com.javaclaw.framework.spi.", "org.springframework.ai.",
                "com.fasterxml.jackson.", "org.slf4j.");

        private SelectiveClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (PARENT_FIRST.stream().anyMatch(name::startsWith)) {
                        loaded = super.loadClass(name, false);
                    } else {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException absent) {
                            loaded = super.loadClass(name, false);
                        }
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
