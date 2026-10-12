package com.javaclaw.plugin.sdk.packaging;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 生成可预览的完整文件清单；签名密钥仅从调用者指定的本地文件读取。 */
public final class PluginPackages {
    private PluginPackages() { }

    public record SigningKey(PrivateKey privateKey, PublicKey publicKey) {
        public SigningKey { Objects.requireNonNull(privateKey); Objects.requireNonNull(publicKey); }
        public static SigningKey read(Path privateKey, Path publicKey) throws Exception {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            return new SigningKey(factory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes(privateKey))),
                    factory.generatePublic(new X509EncodedKeySpec(keyBytes(publicKey))));
        }
    }

    public static Path create(Path manifest, Path artifact, Map<String, Path> additionalFiles,
                              Path destination, SigningKey signingKey) throws Exception {
        JsonObject declaration = JsonParser.parseString(Files.readString(regular(manifest), StandardCharsets.UTF_8))
                .getAsJsonObject();
        if (!declaration.has("protocol") || declaration.getAsJsonObject("protocol").get("major").getAsInt() != 2
                || !declaration.has("entrypoint") || !declaration.has("id")) {
            throw new IllegalArgumentException("仅支持完整的 v2 插件清单");
        }
        String artifactName = declaration.has("artifact") ? declaration.get("artifact").getAsString() : "plugin.jar";
        TreeMap<String, Path> files = new TreeMap<>();
        files.put("plugin.json", regular(manifest));
        files.put(safeName(artifactName), regular(artifact));
        if (files.size() != 2) throw new IllegalArgumentException("插件入口工件名称占用了清单路径");
        for (var entry : additionalFiles.entrySet()) {
            String name = safeName(entry.getKey());
            if (name.equals("files.sha256") || name.equals("signature.json")
                    || files.putIfAbsent(name, regular(entry.getValue())) != null) {
                throw new IllegalArgumentException("重复或保留的插件包路径: " + name);
            }
        }
        StringBuilder hashes = new StringBuilder();
        for (var entry : files.entrySet()) hashes.append(sha256(entry.getValue())).append("  ")
                .append(entry.getKey()).append('\n');
        byte[] signedBytes = hashes.toString().getBytes(StandardCharsets.UTF_8);
        byte[] signature = signingKey == null ? null : signature(signedBytes, signingKey);
        Path output = destination.toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".plugin-package-", ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (var entry : files.entrySet()) {
                    zip.putNextEntry(zipEntry(entry.getKey()));
                    Files.copy(entry.getValue(), zip);
                    zip.closeEntry();
                }
                bytes(zip, "files.sha256", signedBytes);
                if (signature != null) bytes(zip, "signature.json", signature);
            }
            try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException fallback) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return output;
    }

    private static byte[] signature(byte[] bytes, SigningKey key) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.privateKey()); signer.update(bytes);
        byte[] signature = signer.sign();
        signer.initVerify(key.publicKey()); signer.update(bytes);
        if (!signer.verify(signature)) throw new IllegalArgumentException("签名公钥与私钥不匹配");
        JsonObject metadata = new JsonObject();
        metadata.addProperty("algorithm", "Ed25519");
        metadata.addProperty("publicKey", Base64.getEncoder().encodeToString(key.publicKey().getEncoded()));
        metadata.addProperty("signature", Base64.getEncoder().encodeToString(signature));
        return metadata.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] keyBytes(Path path) throws IOException {
        Path checked = regular(path);
        if (Files.size(checked) > 16_384) throw new IOException("密钥文件过大");
        byte[] bytes = Files.readAllBytes(checked);
        String text = new String(bytes, StandardCharsets.US_ASCII);
        if (!text.startsWith("-----BEGIN ")) return bytes;
        return Base64.getDecoder().decode(text.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
    }
    private static Path regular(Path path) throws IOException {
        Path checked = Objects.requireNonNull(path).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(checked) || !Files.isRegularFile(checked, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("插件包输入必须是普通文件");
        }
        return checked;
    }
    private static String safeName(String name) {
        if (name == null || name.isBlank() || name.length() > 512 || !name.matches("[A-Za-z0-9._/-]+")
                || name.startsWith("/") || Arrays.stream(name.split("/", -1))
                .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("插件包路径无效");
        }
        return name;
    }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int length; (length = input.read(buffer)) != -1;) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static ZipEntry zipEntry(String name) { ZipEntry entry = new ZipEntry(name); entry.setTime(0); return entry; }
    private static void bytes(ZipOutputStream zip, String name, byte[] value) throws IOException {
        zip.putNextEntry(zipEntry(name)); zip.write(value); zip.closeEntry();
    }
}
