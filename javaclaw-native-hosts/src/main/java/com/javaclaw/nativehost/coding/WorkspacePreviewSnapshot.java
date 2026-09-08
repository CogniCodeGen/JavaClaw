package com.javaclaw.nativehost.coding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.nativehost.sandbox.SandboxJavaRuntime;
import com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess;

/**
 * 固定文件 Worker 将来源流式复制到服务端独占缓存；正文不经 stdout 或整个 byte[] 传输。
 *
 * <p>调用方先预留磁盘预算并创建受保护的缓存目录。本类型只授予该目录写入，不扩大项目读取范围；返回时 Worker 已退出。
 */
public final class WorkspacePreviewSnapshot {
    private WorkspacePreviewSnapshot() {}

    /**
     * 创建一个有界快照，检测读取期间文件身份和大小变化。
     *
     * @param root 权威执行根
     * @param permission 实时核验后的只读权限
     * @param relative 来源相对路径
     * @param destination 缓存目录中的新文件，必须不存在
     * @param maximumBytes 已预留的磁盘字节数，最多 64 MiB
     * @param cancellation 当前连接取消信号
     * @return 实际大小和完整摘要
     * @throws Exception 原生隔离、权限、变更或 IO 失败
     */
    public static Result capture(
            Path root,
            PermissionProfile permission,
            String relative,
            Path destination,
            long maximumBytes,
            CancellationToken cancellation)
            throws Exception {
        var entry = new WorkspaceFileAccess(root, permission)
                .stat(relative, cancellation)
                .orElseThrow(() -> new IOException("预览文件不存在"));
        if (entry.directory() || entry.size() > maximumBytes || maximumBytes > 64L * 1024 * 1024) {
            throw new IOException("预览来源超过预留预算或不是普通文件");
        }
        Path parent = destination.getParent().toRealPath();
        if (!destination.isAbsolute()
                || !parent.equals(destination.getParent())
                || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("预览缓存目标不是新的受控文件");
        }
        SandboxJavaRuntime runtime = SandboxJavaRuntime.forWorker(WorkspaceFileWorker.class);
        PermissionProfile worker = workerPermission(permission, runtime.executable());
        byte[] request = request(relative, destination, maximumBytes);
        var command = new SandboxCommand(
                "workspace-preview",
                runtime.command(WorkspaceFileWorker.class, List.of(root.toString()), 128),
                worker.files().readRoots().getFirst(),
                Map.of(),
                request,
                SandboxMode.BATCH,
                worker.processes().maxRunTime());
        var result = new PlatformSandboxExecutor()
                .execute(
                        command,
                        worker,
                        cancellation,
                        new SandboxRuntimeAccess(runtime.readRoots(), List.of(parent), List.of(runtime.executable())),
                        SandboxNetworkAccess.offline());
        if (result.cancelled() || result.timedOut() || result.exitCode() != 0) {
            throw new IOException("PREVIEW_READ_FAILED: 文件快照 Worker 未完成");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(result.standardOutput()))) {
            if (!input.readBoolean()) {
                throw new IOException("PREVIEW_SOURCE_CHANGED: " + input.readUTF());
            }
            return new Result(input.readLong(), input.readUTF());
        }
    }

    private static PermissionProfile workerPermission(PermissionProfile source, Path executable) {
        if (source.files().readRoots().isEmpty()) {
            throw new SecurityException("来源没有文件读取权限");
        }
        Duration maximum = source.processes().maxRunTime();
        Duration timeout = maximum.compareTo(Duration.ofSeconds(30)) < 0 ? maximum : Duration.ofSeconds(30);
        return new PermissionProfile(
                "document-preview-worker",
                1,
                new FilePermission(source.files().readRoots(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(executable.getFileName().toString()), false, timeout),
                source.tools(),
                source.resources());
    }

    private static byte[] request(String relative, Path destination, long maximum) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeUTF("preview-snapshot");
            output.writeUTF(relative);
            output.writeUTF(destination.toString());
            output.writeLong(maximum);
        }
        return bytes.toByteArray();
    }

    static Result copy(WorkspaceFileTree tree, String relative, Path destination, long maximum) throws Exception {
        WorkspaceFileProtocol.requireRelative(relative, false);
        if (!destination.isAbsolute() || maximum < 0 || maximum > 64L * 1024 * 1024) {
            throw new IOException("快照路径或预算无效");
        }
        String leaf = Path.of(relative).getFileName().toString();
        try (var parent = tree.directory(WorkspaceFileTree.parent(relative))) {
            var before = parent.attributes(leaf);
            if (!before.isRegularFile() || before.size() > maximum) {
                throw new IOException("快照来源无效或超过预算");
            }
            var digest = MessageDigest.getInstance("SHA-256");
            long total;
            try (var source = parent.openFile(leaf, Set.of(StandardOpenOption.READ));
                    var output = FileChannel.open(
                            destination,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                total = transfer(source, output, digest, maximum);
                output.force(true);
            }
            var after = parent.attributes(leaf);
            if (total != before.size()
                    || !WorkspaceFileTree.sameIdentity(before, after)
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                throw new IOException("文件在读取时发生变化");
            }
            return new Result(total, HexFormat.of().formatHex(digest.digest()));
        }
    }

    private static long transfer(
            java.nio.channels.SeekableByteChannel source, FileChannel output, MessageDigest digest, long maximum)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long total = 0;
        int count;
        while ((count = source.read(buffer)) >= 0) {
            total += count;
            if (total > maximum) {
                throw new IOException("文件增长超过预留预算");
            }
            buffer.flip();
            digest.update(buffer.asReadOnlyBuffer());
            while (buffer.hasRemaining()) {
                output.write(buffer);
            }
            buffer.clear();
        }
        return total;
    }

    /**
     * @param sizeBytes 实际字节数
     * @param digest 全部已复制内容的 SHA-256
     */
    public record Result(long sizeBytes, String digest) {
        /** 校验 Worker 响应边界。 */
        public Result {
            if (sizeBytes < 0 || sizeBytes > 64L * 1024 * 1024 || !digest.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("无效快照回执");
            }
        }
    }
}
