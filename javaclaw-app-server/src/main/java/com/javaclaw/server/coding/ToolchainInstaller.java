package com.javaclaw.server.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.nativehost.ManagedRuntimeDirectory;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;
import com.javaclaw.server.toolchain.ToolchainArtifactDownloadPort;

/** 单实例串行安装、内容寻址复用和原子发布；staging 从不提供给项目进程。 */
final class ToolchainInstaller {
    private static final long MAXIMUM_DOWNLOAD = 512L * 1024 * 1024;
    private static final long MAXIMUM_STORAGE = 8L * 1024 * 1024 * 1024;
    private final Path dataRoot;
    private final Path installations;
    private boolean prepared;
    private final Object initialization = new Object();
    private final Path staging;
    private final ToolchainArtifactDownloadPort downloads;
    private final ToolchainFileEvidence evidence;

    ToolchainInstaller(Path dataRoot, ToolchainArtifactDownloadPort downloads, CanonicalJson json) throws Exception {
        this.dataRoot = dataRoot;
        this.downloads = downloads;
        evidence = new ToolchainFileEvidence(json);
        Path root = dataRoot.resolve("coding/toolchains");
        installations = root.resolve(CodingToolchainCatalog.platform() + "-" + CodingToolchainCatalog.architecture());
        staging = root.resolve("staging");
    }

    private void prepare() throws Exception {
        synchronized (initialization) {
            initialize();
        }
    }

    private void initialize() throws Exception {
        if (prepared) {
            return;
        }
        ManagedRuntimeDirectory.prepare(dataRoot);
        Path root = dataRoot.resolve("coding/toolchains");
        privateDirectory(dataRoot.resolve("coding"));
        privateDirectory(root);
        privateDirectory(installations);
        privateDirectory(staging);
        // App Server 独占数据根；崩溃残留 staging 不是已发布安装，只在首次实际使用时清理。
        try (var stale = Files.list(staging)) {
            for (Path path : stale.toList()) {
                ToolchainFileEvidence.delete(path);
            }
        }
        prepared = true;
    }

    private void privateDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory);
            ToolchainFileEvidence.permissions(directory, true, false);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(dataRoot))) {
            throw new IOException("工具链目录必须是当前用户拥有的普通目录");
        }
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            // coding 是其他平台组件共享的应用目录；仅收紧已确认所有者的子目录，不修复数据根权限。
            Files.setPosixFilePermissions(
                    directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } else {
            var view = Files.getFileAttributeView(directory, java.nio.file.attribute.AclFileAttributeView.class);
            var rootView = Files.getFileAttributeView(dataRoot, java.nio.file.attribute.AclFileAttributeView.class);
            if (view == null || rootView == null || view.getAcl().isEmpty()) {
                throw new IOException("工具链目录缺少可验证 ACL");
            }
            var trusted = rootView.getAcl().stream()
                    .map(java.nio.file.attribute.AclEntry::principal)
                    .toList();
            for (var entry : view.getAcl()) {
                if (entry.type() == java.nio.file.attribute.AclEntryType.ALLOW
                        && !trusted.contains(entry.principal())) {
                    throw new IOException("工具链目录 ACL 包含额外访问主体");
                }
            }
        }
    }

    synchronized Path install(ToolchainArtifact artifact, CancellationToken cancellation) throws Exception {
        cancellation.throwIfCancelled();
        prepare();
        Path destination = root(artifact);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            try {
                evidence.verify(destination, artifact, cancellation);
                ToolchainFileEvidence.permissions(destination, true, true);
                return destination;
            } catch (IOException corrupted) {
                ToolchainFileEvidence.delete(destination);
            }
        }
        long available = MAXIMUM_STORAGE - storageBytes(cancellation);
        if (available <= Math.min(MAXIMUM_DOWNLOAD, artifact.downloadBytes())) {
            throw new IOException("TOOLCHAIN_CACHE_FULL: 托管安装达到 8 GiB 磁盘上限");
        }
        Path temporary = Files.createTempDirectory(staging, "install-");
        try {
            Path archive = temporary.resolve("artifact.archive");
            download(artifact, archive, cancellation);
            Path expanded = Files.createDirectory(temporary.resolve("expanded"));
            new SafeToolchainArchive(expanded, cancellation, available - Files.size(archive))
                    .extract(archive, artifact.archiveFormat());
            evidence.seal(expanded, artifact, cancellation);
            cancellation.throwIfCancelled();
            // macOS 跨父目录 rename 需要更新目录的父项；先保留私有根可写，发布后再收紧。
            ToolchainFileEvidence.permissions(expanded, true, false);
            Files.move(expanded, destination, StandardCopyOption.ATOMIC_MOVE);
            ToolchainFileEvidence.permissions(destination, true, true);
            return destination;
        } finally {
            // 下载缓存只在安装事务期间存在；已发布内容按摘要复用，失败或取消不会留下大型归档。
            ToolchainFileEvidence.delete(temporary);
        }
    }

    Path verify(ToolchainArtifact artifact, CancellationToken cancellation) throws Exception {
        prepare();
        Path root = root(artifact);
        evidence.verify(root, artifact, cancellation);
        return root;
    }

    private Path root(ToolchainArtifact artifact) {
        return installations.resolve(artifact.reference().artifactSha256());
    }

    private void download(ToolchainArtifact artifact, Path archive, CancellationToken cancellation) throws Exception {
        long maximum = Math.min(MAXIMUM_DOWNLOAD, artifact.downloadBytes());
        try (var output = Files.newOutputStream(archive);
                var bounded = new BoundedOutput(output, maximum)) {
            downloads.download(artifact, bounded, cancellation);
        }
        if (!ToolchainFileEvidence.digest(archive, cancellation)
                .equals(artifact.reference().artifactSha256())) {
            throw new IOException("TOOLCHAIN_DIGEST_MISMATCH: 下载制品摘要与发行清单不符");
        }
    }

    private long storageBytes(CancellationToken cancellation) throws IOException {
        long total = 0;
        try (var paths = Files.walk(installations)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                cancellation.throwIfCancelled();
                Path path = iterator.next();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total = Math.addExact(total, Files.size(path));
                }
            }
        }
        return total;
    }

    private static final class BoundedOutput extends java.io.FilterOutputStream {
        private final long maximum;
        private long written;

        private BoundedOutput(java.io.OutputStream output, long maximum) {
            super(output);
            this.maximum = maximum;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > maximum - written) {
                throw new IOException("工具链下载超过字节上限");
            }
            out.write(bytes, offset, length);
            written += length;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }
    }
}
