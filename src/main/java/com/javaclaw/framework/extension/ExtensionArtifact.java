package com.javaclaw.framework.extension;

import com.javaclaw.framework.spi.AgentFrameworkExtension;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Loaded trusted artifact before it is validated and atomically published. */
public record ExtensionArtifact(
        AgentFrameworkExtension extension,
        String artifactSha256,
        Closeable classLoader) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    public ExtensionArtifact {
        extension = Objects.requireNonNull(extension, "extension");
        artifactSha256 = Objects.requireNonNull(artifactSha256, "artifactSha256").toLowerCase();
        if (!SHA_256.matcher(artifactSha256).matches()) {
            throw new IllegalArgumentException("artifact hash must be a SHA-256 hex value");
        }
    }

    public static ExtensionArtifact builtin(AgentFrameworkExtension extension) {
        String seed = extension.descriptor().coordinate();
        return new ExtensionArtifact(extension,
                java.util.HexFormat.of().formatHex(sha256(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                null);
    }

    /** Closes each distinct external loader once when publication rejects candidate artifacts. */
    public static void closeClassLoaders(Collection<ExtensionArtifact> artifacts) {
        Set<Closeable> loaders = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        artifacts.stream().map(ExtensionArtifact::classLoader)
                .filter(Objects::nonNull).forEach(loaders::add);
        RuntimeException first = null;
        for (Closeable loader : loaders) {
            try {
                loader.close();
            } catch (IOException failure) {
                RuntimeException wrapped = new IllegalStateException(
                        "cannot close rejected extension classloader", failure);
                if (first == null) first = wrapped;
                else first.addSuppressed(wrapped);
            }
        }
        if (first != null) throw first;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
