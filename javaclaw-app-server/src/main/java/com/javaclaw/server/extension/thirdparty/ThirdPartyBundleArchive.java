package com.javaclaw.server.extension.thirdparty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;

/** ZIP Bundle 的有界解压、Ed25519 验签和逐文件摘要校验。 */
final class ThirdPartyBundleArchive {
    private static final String MANIFEST = "manifest.json";
    private static final String SIGNATURE = "manifest.sig";
    private static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 16 * 1024;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final CanonicalJson json;
    private final ExtensionTrustedKeys trustedKeys;
    private final ThirdPartyBundleDirectories directories;

    ThirdPartyBundleArchive(
            CanonicalJson json, ExtensionTrustedKeys trustedKeys, ThirdPartyBundleDirectories directories) {
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.trustedKeys = java.util.Objects.requireNonNull(trustedKeys, "trustedKeys");
        this.directories = java.util.Objects.requireNonNull(directories, "directories");
    }

    VerifiedThirdPartyBundle stage(AttachmentContent attachment) {
        Path source = writeManagedArchive(attachment);
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory(directories.stagingRoot(), ".incoming-");
            setPermissions(temporary, DIRECTORY_PERMISSIONS);
            extract(source, temporary);
            VerifiedThirdPartyBundle verified = verifyDirectory(directories.requireStaging(temporary));
            Path target = directories.staging(verified.manifestDigest());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                SafeBundleFileTree.delete(directories.stagingRoot(), temporary);
                temporary = null;
                return verifyStaged(target, verified.manifestDigest());
            }
            try {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException raced) {
                SafeBundleFileTree.delete(directories.stagingRoot(), temporary);
            }
            temporary = null;
            return verifyStaged(target, verified.manifestDigest());
        } catch (IOException failure) {
            throw new IllegalStateException("第三方 Bundle staging 失败", failure);
        } finally {
            deleteTemporary(temporary);
            deleteArchive(source);
        }
    }

    VerifiedThirdPartyBundle verifyStaged(String stagingId) {
        return verifyStaged(directories.staging(stagingId), stagingId);
    }

    VerifiedThirdPartyBundle verifyInstalled(Path root, String expectedDigest) {
        try {
            VerifiedThirdPartyBundle verified = verifyDirectory(directories.requireInstalled(root));
            if (!verified.manifestDigest().equals(expectedDigest)) {
                throw new SecurityException("installed manifest digest changed");
            }
            return verified;
        } catch (IOException failure) {
            throw new IllegalStateException("installed Bundle 目录校验失败", failure);
        }
    }

    private VerifiedThirdPartyBundle verifyStaged(Path root, String expectedDigest) {
        try {
            VerifiedThirdPartyBundle verified = verifyDirectory(directories.requireStaging(root));
            if (!verified.manifestDigest().equals(expectedDigest)) {
                throw new SecurityException("staging identity does not match manifest digest");
            }
            return verified;
        } catch (IOException failure) {
            throw new IllegalStateException("staging Bundle 目录校验失败", failure);
        }
    }

    private VerifiedThirdPartyBundle verifyDirectory(Path directory) {
        try {
            Path root = directory.toRealPath();
            byte[] manifestBytes = readRegular(root.resolve(MANIFEST), MAX_MANIFEST_BYTES);
            var canonicalManifest = json.parse(new String(manifestBytes, StandardCharsets.UTF_8));
            byte[] canonicalBytes = canonicalManifest.json().getBytes(StandardCharsets.UTF_8);
            String manifestDigest = digest(canonicalBytes);
            ThirdPartyBundleManifest manifest = json.decode(canonicalManifest, ThirdPartyBundleManifest.class);
            ThirdPartyPermissionPolicy.validate(manifest, json);
            byte[] signature = decodeSignature(readRegular(root.resolve(SIGNATURE), MAX_SIGNATURE_BYTES));
            trustedKeys.verify(manifest.signingKeyId(), canonicalBytes, signature);
            verifyFiles(root, manifest);
            Path executable = root.resolve(manifest.entryPoint().executable()).normalize();
            requireContained(root, executable);
            if (!Files.isExecutable(executable)) {
                setPermissions(executable, EXECUTABLE_PERMISSIONS);
            }
            if (!Files.isExecutable(executable)) {
                throw new SecurityException("bundle entryPoint is not executable");
            }
            return new VerifiedThirdPartyBundle(
                    manifest, manifestDigest, trustedKeys.fingerprint(manifest.signingKeyId()), root);
        } catch (IOException failure) {
            throw new IllegalStateException("第三方 Bundle 内容校验失败", failure);
        }
    }

    private Path writeManagedArchive(AttachmentContent attachment) {
        AttachmentContent checked = java.util.Objects.requireNonNull(attachment, "attachment");
        if (!BundleRpcContracts.BUNDLE_MEDIA_TYPE.equals(checked.metadata().mediaType())) {
            throw new IllegalArgumentException("Bundle Attachment media type 不受支持");
        }
        byte[] content = checked.content();
        if (content.length < 1 || content.length > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("Bundle Attachment size is invalid");
        }
        if (!digest(content).equals(checked.metadata().digest())) {
            throw new SecurityException("Bundle Attachment digest changed");
        }
        try {
            Path archive = Files.createTempFile(directories.stagingRoot(), ".attachment-", ".zip");
            setPermissions(archive, FILE_PERMISSIONS);
            Files.write(archive, content, StandardOpenOption.TRUNCATE_EXISTING);
            return archive;
        } catch (IOException failure) {
            throw new IllegalStateException("无法物化受管 Bundle Attachment", failure);
        }
    }

    private static void extract(Path archive, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            HashSet<String> names = new HashSet<>();
            long expanded = 0;
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new SecurityException("bundle contains too many entries");
                }
                String name = normalizedEntryName(entry);
                if (!names.add(name)) {
                    throw new SecurityException("bundle contains a duplicate path: " + name);
                }
                Path output = target.resolve(name).normalize();
                requireContained(target, output);
                if (entry.isDirectory()) {
                    secureDirectories(output);
                    continue;
                }
                secureDirectories(output.getParent());
                long copied;
                try (InputStream input = zip.getInputStream(entry);
                        OutputStream destination = Files.newOutputStream(
                                output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    copied = copyBounded(input, destination, MAX_ENTRY_BYTES);
                }
                setPermissions(output, FILE_PERMISSIONS);
                if (entry.getSize() >= 0 && copied != entry.getSize()) {
                    throw new SecurityException("bundle entry size changed while reading: " + name);
                }
                expanded = Math.addExact(expanded, copied);
                if (expanded > MAX_EXPANDED_BYTES) {
                    throw new SecurityException("bundle expanded size exceeds platform ceiling");
                }
            }
        } catch (ArithmeticException overflow) {
            throw new SecurityException("bundle expanded size overflow", overflow);
        }
    }

    private static String normalizedEntryName(ZipEntry entry) {
        String name = entry.getName();
        if (entry.isDirectory() && name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        return BundlePathNames.requireRelative(name, "zip entry");
    }

    private static long copyBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total = Math.addExact(total, read);
            if (total > maximum) {
                throw new SecurityException("bundle entry exceeds size ceiling");
            }
            output.write(buffer, 0, read);
        }
        return total;
    }

    private static void verifyFiles(Path root, ThirdPartyBundleManifest manifest) throws IOException {
        Map<String, ThirdPartyBundleManifest.BundleFile> declared = new LinkedHashMap<>();
        manifest.files().forEach(file -> declared.put(file.path(), file));
        List<String> actual = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(path -> !path.equals(root)).forEach(path -> {
                if (Files.isSymbolicLink(path)) {
                    throw new SecurityException("bundle contains a symbolic link");
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    if (!relative.equals(MANIFEST) && !relative.equals(SIGNATURE)) {
                        actual.add(relative);
                    }
                }
            });
        }
        if (!Set.copyOf(actual).equals(declared.keySet())) {
            throw new SecurityException("bundle files differ from signed manifest");
        }
        for (String relative : actual) {
            Path path = root.resolve(relative).normalize();
            requireContained(root, path);
            ThirdPartyBundleManifest.BundleFile expected = declared.get(relative);
            if (Files.size(path) != expected.sizeBytes() || !digest(path).equals(expected.sha256())) {
                throw new SecurityException("bundle file digest differs from manifest: " + relative);
            }
        }
    }

    private static byte[] readRegular(Path path, int maximum) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("required bundle metadata is not a regular file: " + path.getFileName());
        }
        long size = Files.size(path);
        if (size < 1 || size > maximum) {
            throw new SecurityException("bundle metadata size is invalid: " + path.getFileName());
        }
        return Files.readAllBytes(path);
    }

    private static byte[] decodeSignature(byte[] encoded) {
        try {
            return Base64.getDecoder().decode(new String(encoded, StandardCharsets.US_ASCII).strip());
        } catch (IllegalArgumentException failure) {
            throw new SecurityException("manifest.sig is not valid Base64", failure);
        }
    }

    private static String digest(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String digest(byte[] content) {
        return HexFormat.of().formatHex(sha256().digest(content));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void requireContained(Path root, Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new SecurityException("bundle path escapes staging root");
        }
    }

    private static void secureDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
        setPermissions(directory, DIRECTORY_PERMISSIONS);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 使用安装目录 ACL；Java NIO 不在此处扩大权限。
        }
    }

    private void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            SafeBundleFileTree.delete(directories.stagingRoot(), temporary);
        } catch (IOException ignored) {
            // 仅可能残留在受管 staging 根，下次启动会再次校验并清理。
        }
    }

    private static void deleteArchive(Path archive) {
        try {
            Files.deleteIfExists(archive);
        } catch (IOException ignored) {
            // 文件位于受管 staging 根且从未暴露给 Worker；下次启动可安全清理。
        }
    }
}
