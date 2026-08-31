package com.javaclaw.server.extension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Canonical Plugin 4.0 Ed25519 payload: unsigned manifest plus sorted file digest list. */
public final class PluginSignaturePayload {
    private static final byte[] PREFIX = "JAVACLAW-PLUGIN-SIGNATURE-V1\n".getBytes(StandardCharsets.UTF_8);

    private PluginSignaturePayload() {}

    /**
     * 生成去除 signature 的规范 JSON 与按路径排序的 bundle SHA-256 清单，形成稳定验签载荷。
     *
     * @throws java.io.IOException 包文件无法安全读取或规范载荷无法生成
     */
    public static byte[] create(byte[] manifestBytes, Path bundleRoot) throws IOException {
        ObjectMapper json = new ObjectMapper()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        ObjectNode manifest;
        try {
            manifest = (ObjectNode) json.readTree(manifestBytes);
        } catch (RuntimeException failure) {
            throw new IOException("cannot canonicalize plugin manifest", failure);
        }
        manifest.remove("signature");
        byte[] canonical = json.writeValueAsBytes(manifest);
        Path root = bundleRoot.toRealPath();
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(path -> !path.equals(root))
                    .sorted(Comparator.comparing(path -> portable(root.relativize(path))))
                    .toList();
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(PREFIX);
        payload.write(canonical);
        payload.write('\n');
        for (Path path : files) {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("plugin signature payload rejects symlinks: " + portable(root.relativize(path)));
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("plugin bundle contains a special file");
            }
            String relative = portable(root.relativize(path));
            if (PluginBundleLoader.MANIFEST_PATH.equals(relative)) {
                continue;
            }
            payload.write(relative.getBytes(StandardCharsets.UTF_8));
            payload.write(0);
            payload.write(HexFormat.of().formatHex(sha256(path)).getBytes(StandardCharsets.US_ASCII));
            payload.write('\n');
        }
        return payload.toByteArray();
    }

    private static byte[] sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }

    private static String portable(Path relative) {
        return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
    }
}
