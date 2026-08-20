package com.javaclaw.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Forces standard JAR signature verification without loading any plugin class. */
final class ServicePluginJarSignatureVerifier {
    boolean hasSignatureMetadata(Path path) throws IOException {
        if (path == null || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        boolean signatureFile = false;
        boolean signatureBlock = false;
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String upper = entries.nextElement().getName().toUpperCase(java.util.Locale.ROOT);
                if (!upper.startsWith("META-INF/")) continue;
                String leaf = upper.substring("META-INF/".length());
                if (leaf.contains("/")) continue;
                if (leaf.endsWith(".SF")) signatureFile = true;
                if (leaf.endsWith(".RSA") || leaf.endsWith(".DSA")
                        || leaf.endsWith(".EC") || leaf.startsWith("SIG-")) signatureBlock = true;
            }
        }
        return signatureFile || signatureBlock;
    }

    Verification verify(Path path) throws IOException {
        if (path == null || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("服务插件 JAR 不是普通文件");
        }
        Set<String> signerKeys = new LinkedHashSet<>();
        String publisher = "";
        int signedEntries = 0;
        try (JarFile jar = new JarFile(path.toFile(), true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || isSignatureMetadata(entry.getName())) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                } catch (SecurityException invalid) {
                    throw new IOException("服务插件 JAR 签名校验失败: " + entry.getName(), invalid);
                }
                Certificate[] certificates = entry.getCertificates();
                if (certificates == null || certificates.length == 0) {
                    throw new IOException("服务插件包含未签名内容: " + entry.getName());
                }
                signedEntries++;
                Certificate signer = certificates[0];
                signerKeys.add(sha256(signer.getPublicKey().getEncoded()));
                if (publisher.isBlank() && signer instanceof X509Certificate x509) {
                    publisher = x509.getSubjectX500Principal().getName();
                }
            }
        }
        if (signedEntries == 0 || signerKeys.size() != 1) {
            throw new IOException("服务插件必须由一个一致的发布者签署全部内容");
        }
        String signerKey = signerKeys.iterator().next();
        if (publisher.isBlank()) publisher = "key-sha256:" + signerKey.substring(0, 16);
        return new Verification(publisher, signerKey, sha256(path));
    }

    private static boolean isSignatureMetadata(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if ("META-INF/MANIFEST.MF".equals(upper)) return true;
        if (!upper.startsWith("META-INF/")) return false;
        String leaf = upper.substring("META-INF/".length());
        // META-INF/versions and service/config resources remain executable input and must be signed.
        return !leaf.contains("/") && (leaf.endsWith(".SF") || leaf.endsWith(".RSA")
                || leaf.endsWith(".DSA") || leaf.endsWith(".EC") || leaf.startsWith("SIG-"));
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record Verification(String publisher, String signerKeySha256, String artifactSha256) { }
}
