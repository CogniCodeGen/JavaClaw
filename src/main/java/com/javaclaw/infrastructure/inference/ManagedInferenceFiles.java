package com.javaclaw.infrastructure.inference;

import com.javaclaw.inference.api.InferenceModelAsset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Shared, symlink-safe file operations for the managed model store. */
final class ManagedInferenceFiles {

    private static final Logger log = LoggerFactory.getLogger(ManagedInferenceFiles.class);
    private static final int BUFFER_SIZE = 1024 * 1024;

    private ManagedInferenceFiles() { }

    static List<SourceFile> inspect(Path root, BooleanSupplier cancelled) throws Exception {
        List<SourceFile> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                checkCancelledIo(cancelled);
                if (Files.isSymbolicLink(dir) || !attrs.isDirectory()) {
                    throw new IOException("模型目录包含不允许的目录项: " + dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                checkCancelledIo(cancelled);
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                    throw new IOException("模型目录包含符号链接或特殊文件: " + file);
                }
                String relative = normalizeRelative(root.relativize(file));
                try {
                    files.add(new SourceFile(file, relative, attrs.size(), sha256(file, cancelled)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("模型导入已取消", interrupted);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(SourceFile::relative));
        return List.copyOf(files);
    }

    static AssetSnapshot metadataSnapshot(Path root, BooleanSupplier cancelled) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("模型资产目录不可用");
        }
        List<FileStamp> values = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                checkCancelledIo(cancelled);
                if (Files.isSymbolicLink(dir) || !attrs.isDirectory()) {
                    throw new IOException("模型目录包含不允许的目录项: " + dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                checkCancelledIo(cancelled);
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                    throw new IOException("模型目录包含符号链接或特殊文件: " + file);
                }
                values.add(new FileStamp(normalizeRelative(root.relativize(file)), attrs.size(),
                        attrs.lastModifiedTime().toMillis(), String.valueOf(attrs.fileKey())));
                return FileVisitResult.CONTINUE;
            }
        });
        values.sort(Comparator.comparing(FileStamp::path));
        return new AssetSnapshot(List.copyOf(values));
    }

    static void requireManifestMetadata(
            List<InferenceModelAsset.AssetFile> expected, List<FileStamp> actual)
            throws IOException {
        if (expected.size() != actual.size()) throw new IOException("模型资产文件集合已变化");
        List<InferenceModelAsset.AssetFile> sorted = expected.stream()
                .sorted(Comparator.comparing(InferenceModelAsset.AssetFile::path)).toList();
        for (int index = 0; index < sorted.size(); index++) {
            if (!sorted.get(index).path().equals(actual.get(index).path())
                    || sorted.get(index).size() != actual.get(index).size()) {
                throw new IOException("模型资产文件集合或长度已变化: " + actual.get(index).path());
            }
        }
    }

    static boolean sameContent(
            List<InferenceModelAsset.AssetFile> manifest, List<SourceFile> cached) {
        if (manifest.size() != cached.size()) return false;
        for (int index = 0; index < manifest.size(); index++) {
            InferenceModelAsset.AssetFile expected = manifest.get(index);
            SourceFile actual = cached.get(index);
            if (!expected.path().equals(actual.relative()) || expected.size() != actual.size()
                    || !expected.sha256().equals(actual.sha256())) return false;
        }
        return true;
    }

    static boolean sameSourceContent(List<SourceFile> expected, List<SourceFile> actual) {
        if (expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            SourceFile left = expected.get(index);
            SourceFile right = actual.get(index);
            if (!left.relative().equals(right.relative()) || left.size() != right.size()
                    || !left.sha256().equals(right.sha256())) return false;
        }
        return true;
    }

    static void verifyCachedContent(
            Path target, List<InferenceModelAsset.AssetFile> manifest, String contentHash,
            BooleanSupplier cancelled) throws Exception {
        List<SourceFile> cached = inspect(target, cancelled);
        if (!sameContent(manifest, cached)) {
            throw new IOException("内容寻址模型缓存与摘要不一致: " + contentHash);
        }
    }

    static void validateModelFileSet(List<SourceFile> files) throws IOException {
        boolean config = files.stream().anyMatch(file -> file.relative().equals("config.json"));
        boolean weights = files.stream()
                .anyMatch(file -> file.relative().endsWith(".safetensors"));
        boolean tokenizer = files.stream().anyMatch(file -> {
            String name = Path.of(file.relative()).getFileName().toString();
            return name.startsWith("tokenizer") || name.endsWith(".model");
        });
        if (!config || !weights || !tokenizer) {
            throw new IOException("模型目录必须包含 config.json、tokenizer 和 safetensors 权重");
        }
    }

    static Path validateRoot(Path source) throws IOException {
        if (source == null) throw new IllegalArgumentException("模型目录不能为空");
        Path absolute = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)
                || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("模型来源不是普通目录: " + absolute);
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    static void createDirectoryTree(Path root, Path directory) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        requireUnder(normalizedRoot, normalizedDirectory);
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(normalizedRoot);
        }
        requirePlainDirectory(normalizedRoot);
        Path current = normalizedRoot;
        for (Path component : normalizedRoot.relativize(normalizedDirectory)) {
            current = current.resolve(component);
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException exists) {
                requirePlainDirectory(current);
            }
        }
    }

    static void requirePlainDirectory(Path value) throws IOException {
        if (Files.isSymbolicLink(value)
                || !Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("模型下载目录包含符号链接或非目录项: " + value);
        }
    }

    static void rejectUnsafeExistingFile(Path value) throws IOException {
        if (!Files.exists(value, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(value)
                || !Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("模型下载目标包含符号链接或特殊文件: " + value);
        }
    }

    static String contentHash(List<SourceFile> files) {
        MessageDigest digest = digest();
        for (SourceFile file : files.stream()
                .sorted(Comparator.comparing(SourceFile::relative)).toList()) {
            updateDigest(digest, file.relative(), file.size(), file.sha256());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(Path file, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                requireNotCancelled(cancelled);
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void updateDigest(MessageDigest digest, String path, long size, String sha) {
        digest.update(path.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(size).array());
        digest.update(HexFormat.of().parseHex(sha));
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }

    static Path safeResolve(Path root, String relative) throws IOException {
        validateRelative(relative);
        Path target = root.resolve(relative).toAbsolutePath().normalize();
        requireUnder(root, target);
        return target;
    }

    static String normalizeRelative(Path relative) throws IOException {
        String value = relative.toString().replace('\\', '/');
        validateRelative(value);
        return value;
    }

    static void validateRelative(String value) throws IOException {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IOException("模型文件路径无效");
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")
                || !path.toString().replace('\\', '/').equals(value)) {
            throw new IOException("模型文件路径越界: " + value);
        }
    }

    static void requireUnder(Path root, Path candidate) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!candidate.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
            throw new SecurityException("文件路径超出推理数据目录");
        }
    }

    static boolean sameFileStore(Path source, Path targetDirectory) {
        try {
            return Files.getFileStore(source).equals(Files.getFileStore(targetDirectory));
        } catch (IOException unavailable) {
            return false;
        }
    }

    static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    static void copyDirectory(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source)
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("待复制模型目录不安全: " + source);
        }
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (Files.isSymbolicLink(dir) || !attrs.isDirectory()) {
                    throw new IOException("模型目录包含符号链接或特殊目录: " + dir);
                }
                Path destination = target.resolve(source.relativize(dir))
                        .toAbsolutePath().normalize();
                requireUnder(target, destination);
                Files.createDirectories(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                    throw new IOException("模型目录包含符号链接或特殊文件: " + file);
                }
                Path destination = target.resolve(source.relativize(file))
                        .toAbsolutePath().normalize();
                requireUnder(target, destination);
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteEmptyDirectories(Path root) {
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        try (var values = Files.list(path)) {
                            if (values.findAny().isEmpty()) Files.deleteIfExists(path);
                        }
                    }
                } catch (IOException cleanupFailure) {
                    log.debug("未能清理空模型目录 {}: {}", path, cleanupFailure.getMessage());
                }
            }
        } catch (IOException scanFailure) {
            log.debug("未能扫描空模型目录 {}: {}", root, scanFailure.getMessage());
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                    throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void requireNotCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new InterruptedException("模型资产操作已取消");
        }
    }

    private static void checkCancelledIo(BooleanSupplier cancelled) throws IOException {
        try {
            requireNotCancelled(cancelled);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("模型资产操作已取消", interrupted);
        }
    }

    record SourceFile(Path source, String relative, long size, String sha256) { }
    record FileStamp(String path, long size, long modifiedMillis, String fileKey) { }
    record AssetSnapshot(List<FileStamp> files) { }
}
