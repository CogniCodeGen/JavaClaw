package com.javaclaw.server.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.protocol.CanonicalJson;

/** 安装证据覆盖所有普通文件的摘要；每次获取租约都验证，不能仅凭目录存在声明就绪。 */
final class ToolchainFileEvidence {
    private static final String MANIFEST = ".javaclaw-installation.json";
    private final CanonicalJson json;

    ToolchainFileEvidence(CanonicalJson json) {
        this.json = json;
    }

    void seal(Path root, ToolchainArtifact artifact, CancellationToken cancellation) throws Exception {
        Map<String, String> files = snapshot(root, cancellation);
        Manifest manifest = new Manifest(artifact.reference().artifactSha256(), files);
        Files.writeString(root.resolve(MANIFEST), json.encode(manifest).json());
        verify(root, artifact, cancellation);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                permissions(path, Files.isDirectory(path) || Files.isExecutable(path), true);
            }
        }
    }

    void verify(Path root, ToolchainArtifact artifact, CancellationToken cancellation) throws Exception {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(root.resolve(MANIFEST), LinkOption.NOFOLLOW_LINKS)
                || Files.size(root.resolve(MANIFEST)) > 16L * 1024 * 1024) {
            throw new IOException("工具链安装缺少完整性证据");
        }
        Manifest manifest;
        try {
            manifest = json.decode(json.parse(Files.readString(root.resolve(MANIFEST))), Manifest.class);
        } catch (RuntimeException invalid) {
            throw new IOException("工具链安装证据格式损坏", invalid);
        }
        if (!manifest.artifactSha256().equals(artifact.reference().artifactSha256())
                || !manifest.files().equals(snapshot(root, cancellation))) {
            throw new IOException("工具链文件完整性校验失败");
        }
        for (String relative : artifact.executablePaths().values()) {
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("工具链缺少发行目录声明的入口");
            }
        }
    }

    private Map<String, String> snapshot(Path root, CancellationToken cancellation) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            int entries = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                cancellation.throwIfCancelled();
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("安装目录不能包含链接");
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !path.equals(root.resolve(MANIFEST))) {
                    result.put(root.relativize(path).toString().replace('\\', '/'), digest(path, cancellation));
                } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && !path.equals(root.resolve(MANIFEST))) {
                    throw new IOException("安装目录包含特殊文件");
                }
                if (++entries > 100_001) {
                    throw new IOException("安装文件数量超过上限");
                }
            }
        }
        return Map.copyOf(result);
    }

    static String digest(Path path, CancellationToken cancellation) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                cancellation.throwIfCancelled();
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void permissions(Path path, boolean executable, boolean readOnly) throws IOException {
        try {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ);
            if (executable) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
            }
            if (!readOnly) {
                permissions.add(PosixFilePermission.OWNER_WRITE);
            }
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException windows) {
            Files.setAttribute(path, "dos:readonly", readOnly, LinkOption.NOFOLLOW_LINKS);
        }
    }

    static void delete(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            var entries = paths.toList();
            for (Path path : entries) {
                if (!Files.isSymbolicLink(path)) {
                    permissions(path, Files.isDirectory(path) || Files.isExecutable(path), false);
                }
            }
            for (Path path : entries.reversed()) {
                Files.delete(path);
            }
        }
    }

    private record Manifest(String artifactSha256, Map<String, String> files) {}
}
