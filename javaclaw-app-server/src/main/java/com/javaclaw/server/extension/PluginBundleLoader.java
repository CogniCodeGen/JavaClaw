package com.javaclaw.server.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads and validates a bundle without loading any bytecode into the current JVM. */
public final class PluginBundleLoader {
    public static final String MANIFEST_PATH = ".javaclaw-plugin/plugin.json";
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private final ObjectMapper json;
    private final PluginSignatureVerifier signatures;

    /** 绑定来源签名验证器；加载只解析声明和路径，不创建 ClassLoader。 */
    public PluginBundleLoader(PluginSignatureVerifier signatures) {
        this.signatures = Objects.requireNonNull(signatures, "signatures");
        this.json = new ObjectMapper();
        json.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        json.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        json.getFactory()
                .setStreamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(32)
                        .maxStringLength(256 * 1024)
                        .maxNumberLength(128)
                        .build());
    }

    /**
     * 严格加载已解包目录并验证签名、贡献入口和包内路径；此入口不允许未签名来源。
     *
     * @throws java.io.IOException 包结构、签名或安全路径检查失败
     */
    public LoadedPlugin load(Path bundleDirectory) throws IOException {
        return load(bundleDirectory, false);
    }

    /** allowUnsigned is set only after an interactive provenance confirmation. */
    public LoadedPlugin load(Path bundleDirectory, boolean allowUnsigned) throws IOException {
        Path root = Objects.requireNonNull(bundleDirectory, "bundleDirectory").toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("plugin bundle is not a directory: " + root);
        }
        Path manifestPath = resolveExisting(root, MANIFEST_PATH, true);
        long size = Files.size(manifestPath);
        if (size < 1 || size > MAX_MANIFEST_BYTES) {
            throw new IOException("plugin manifest size is invalid");
        }
        byte[] bytes = Files.readAllBytes(manifestPath);
        PluginManifest manifest;
        try {
            manifest = json.readValue(bytes, PluginManifest.class);
        } catch (RuntimeException failure) {
            throw new IOException("invalid Plugin 4.0 manifest", failure);
        }
        boolean verified = false;
        if (manifest.signature() != null) {
            try {
                verified = signatures.verify(
                        PluginSignaturePayload.create(bytes.clone(), root), root, manifest.signature());
            } catch (Exception failure) {
                throw new IOException("plugin signature verification failed", failure);
            }
            if (!verified) {
                throw new IOException("plugin signature is invalid or untrusted");
            }
        } else if (!allowUnsigned) {
            throw new IOException("unsigned plugin requires explicit interactive provenance confirmation");
        }

        ArrayList<LoadedPlugin.ResolvedProcess> processes = new ArrayList<>();
        for (PluginProcessContribution process : manifest.processes()) {
            Path entrypoint = resolveExisting(root, process.entrypointForCurrentPlatform(), true);
            processes.add(new LoadedPlugin.ResolvedProcess(process, entrypoint));
        }
        ArrayList<LoadedPlugin.ResolvedSkill> skills = new ArrayList<>();
        for (PluginSkillContribution skill : manifest.skills()) {
            Path path = resolveExisting(root, skill.path(), true);
            skills.add(new LoadedPlugin.ResolvedSkill(skill, path));
        }
        return new LoadedPlugin(manifest, root, verified, processes, skills);
    }

    private static Path resolveExisting(Path root, String relative, boolean regularFile) throws IOException {
        Path unresolved = root.resolve(PluginValidation.relativePath(relative, "bundle path"))
                .normalize();
        if (!unresolved.startsWith(root)) {
            throw new IOException("plugin path escapes bundle");
        }
        Path resolved = unresolved.toRealPath();
        if (!resolved.startsWith(root)) {
            throw new IOException("plugin symlink escapes bundle");
        }
        if (regularFile && !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("plugin path is not a regular file: " + relative);
        }
        return resolved;
    }
}
