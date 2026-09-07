package com.javaclaw.nativehost.coding;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.nativehost.sandbox.SandboxJavaRuntime;

/**
 * 绑定服务端冻结 Workspace 根的受控文件端口，固定 Worker 在原生断网 Sandbox 中执行文件操作。
 *
 * <p>调用方拥有本实例，并须对同一 Workspace 的命令、补丁及恢复串行化。prepare 不写文件； 调用方持久保存 before 后才能 apply。文件系统与 H2 没有共同事务，异常或进程终止后必须按快照核对恢复，
 * 不得自动重放。POSIX 修改以原子交换保存原 inode；Windows 修改为有短暂缺失窗口的两阶段移动。 这不是文件与数据库之间的事务，也不把摘要检查声称为原子 CAS。实际被替换的文件保留于恢复目录。
 */
public final class WorkspaceFileAccess {
    static final int MAX_BYTES = 64 * 1024 * 1024;
    static final int MAX_ENTRIES = 10_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final Path root;
    private final Optional<PermissionProfile> effectivePermission;

    /**
     * 绑定已由服务端核验的真实项目目录，不接受模型指定的根。
     *
     * @param frozenRoot 已冻结的 Workspace 根
     * @throws IOException 根不存在或不是目录
     */
    public WorkspaceFileAccess(Path frozenRoot) throws IOException {
        this(frozenRoot, Optional.empty());
    }

    /**
     * 绑定冻结目录和本次工具调用的有效文件与资源权限。
     *
     * @param frozenRoot 已确认项目根
     * @param permission 经服务端交集及实时撤销检查后的权限
     * @throws IOException 根不存在或不是目录
     */
    public WorkspaceFileAccess(Path frozenRoot, PermissionProfile permission) throws IOException {
        this(frozenRoot, Optional.of(permission));
    }

    private WorkspaceFileAccess(Path frozenRoot, Optional<PermissionProfile> permission) throws IOException {
        root = Objects.requireNonNull(frozenRoot, "frozenRoot");
        effectivePermission = permission;
        WorkspaceFileMetadata.requireFrozenRoot(root);
    }

    /**
     * 读取普通文件快照，文件不存在时返回 exists=false。
     *
     * @param path 根内相对路径
     * @param maxBytes 字节上限，1 至 64 MiB
     * @param cancellation 本次操作取消令牌
     * @return 不含符号链接目标的不可变快照
     * @throws Exception 越界、输出限额、原生隔离或 IO 失败
     */
    public synchronized Snapshot read(String path, int maxBytes, CancellationToken cancellation) throws Exception {
        requirePathPermission(path, false, false);
        WorkspaceFileProtocol.limit(maxBytes, MAX_BYTES, "maxBytes");
        try (DataInputStream input = invoke("read", false, cancellation, output -> {
            output.writeUTF(path);
            output.writeInt(maxBytes);
        })) {
            return WorkspaceFileProtocol.readSnapshot(input);
        }
    }

    /**
     * 只读取普通文件或目录的元数据，不枚举目录、不读取文件正文。
     *
     * @param path 根内相对路径，空或点表示冻结根
     * @param cancellation 取消令牌
     * @return 缺失时为空；目录大小为零，根的路径为点
     * @throws Exception 链接、越权、原生隔离或 IO 失败，不转换为缺失
     */
    public synchronized Optional<Entry> stat(String path, CancellationToken cancellation) throws Exception {
        requirePathPermission(path, true, false);
        try (DataInputStream input = invoke("stat", false, cancellation, output -> output.writeUTF(path))) {
            return WorkspaceFileProtocol.readEntries(input).stream().findFirst();
        }
    }

    /**
     * 流式计算完整摘要并返回字节页，不为 offset 分配内存。
     *
     * @param path 相对文件路径
     * @param offsetBytes 非负起始字节位置
     * @param maxBytes 最多返回字节数
     * @param maxScanBytes 完整摘要扫描的累计字节预算
     * @param cancellation 取消令牌
     * @return 完整文件大小、摘要及有界字节页
     * @throws Exception 文件超过扫描预算、冲突或原生隔离失败
     */
    public synchronized WorkspaceReadPage read(
            String path, long offsetBytes, int maxBytes, int maxScanBytes, CancellationToken cancellation)
            throws Exception {
        requirePathPermission(path, false, false);
        WorkspaceFileProtocol.limit(maxBytes, MAX_BYTES, "maxBytes");
        WorkspaceFileProtocol.limit(maxScanBytes, MAX_BYTES, "maxScanBytes");
        if (offsetBytes < 0) {
            throw new IllegalArgumentException("offsetBytes must be non-negative");
        }
        try (DataInputStream input = invoke("read-page", false, cancellation, output -> {
            output.writeUTF(path);
            output.writeLong(offsetBytes);
            output.writeInt(maxBytes);
            output.writeInt(maxScanBytes);
        })) {
            return new WorkspaceReadPage(
                    input.readUTF(),
                    input.readLong(),
                    input.readUTF(),
                    input.readLong(),
                    WorkspaceFileProtocol.readBytes(input));
        }
    }

    /**
     * 列出一层目录；达到限额时失败，不静默丢弃剩余条目。
     *
     * @param path 相对目录，空字符串表示根
     * @param maxEntries 最大条目数，1 至 10000
     * @param cancellation 取消令牌
     * @return 按路径排序的条目，不跟随链接
     * @throws Exception 路径、隔离、限额或 IO 失败
     */
    public synchronized List<Entry> list(String path, int maxEntries, CancellationToken cancellation) throws Exception {
        requirePathPermission(path, true, false);
        WorkspaceFileProtocol.limit(maxEntries, MAX_ENTRIES, "maxEntries");
        try (DataInputStream input = invoke("list", false, cancellation, output -> {
            output.writeUTF(path);
            output.writeInt(maxEntries);
        })) {
            return WorkspaceFileProtocol.readEntries(input);
        }
    }

    /**
     * 按文件名游标列出目录页，最多扫描 10000 个条目。
     *
     * @param path 相对目录，点或空表示根
     * @param afterName 上页末尾文件名，首批为空
     * @param maxEntries 最大返回条目数
     * @param cancellation 取消令牌
     * @return 有序条目及可选下一页游标
     * @throws Exception 路径、扫描预算或原生隔离失败
     */
    public synchronized WorkspaceDirectoryPage list(
            String path, Optional<String> afterName, int maxEntries, CancellationToken cancellation) throws Exception {
        requirePathPermission(path, true, false);
        WorkspaceFileProtocol.limit(maxEntries, MAX_ENTRIES, "maxEntries");
        Objects.requireNonNull(afterName, "afterName");
        try (DataInputStream input = invoke("list-page", false, cancellation, output -> {
            output.writeUTF(path);
            output.writeUTF(afterName.orElse(""));
            output.writeInt(maxEntries);
        })) {
            List<Entry> entries = WorkspaceFileProtocol.readEntries(input);
            return new WorkspaceDirectoryPage(
                    entries, input.readBoolean() ? Optional.of(input.readUTF()) : Optional.empty());
        }
    }

    /**
     * 对根内普通 UTF-8 文件做有界字面量搜索；匹配数达到上限即结束，不解释正则或 shell。
     *
     * @param path 相对目录或文件，空字符串表示根
     * @param literal 非空搜索文本
     * @param maxMatches 最大匹配行数
     * @param maxBytes 扫描文件内容的累计字节上限
     * @param cancellation 取消令牌
     * @return 相对路径、行号及匹配行
     * @throws Exception 路径、隔离或累计扫描限额失败
     */
    public synchronized List<Match> search(
            String path, String literal, int maxMatches, int maxBytes, CancellationToken cancellation)
            throws Exception {
        requirePathPermission(path, true, false);
        return search(path, literal, "**", true, maxMatches, maxBytes, cancellation);
    }

    /**
     * 按相对路径 glob 和大小写选项执行有界字面搜索。
     *
     * @param path 相对搜索起点
     * @param literal 非空字面查询
     * @param glob Java glob 路径过滤式，最多 1000 字符
     * @param caseSensitive 是否区分大小写
     * @param maxMatches 最大匹配行数
     * @param maxBytes 累计扫描字节数
     * @param cancellation 取消令牌
     * @return 有界匹配行
     * @throws Exception 路径、模式、预算或隔离失败
     */
    public synchronized List<Match> search(
            String path,
            String literal,
            String glob,
            boolean caseSensitive,
            int maxMatches,
            int maxBytes,
            CancellationToken cancellation)
            throws Exception {
        return searchPage(path, literal, glob, caseSensitive, maxMatches, maxBytes, cancellation)
                .matches();
    }

    /**
     * 返回包括真实扫描量与截断标记的有界搜索结果。
     *
     * @param path 相对搜索起点
     * @param literal 非空字面查询
     * @param glob 相对路径过滤式
     * @param caseSensitive 是否区分大小写
     * @param maxMatches 最大匹配行数
     * @param maxBytes 累计扫描字节预算
     * @param cancellation 取消令牌
     * @return 匹配行、实际读取字节及截断标记
     * @throws Exception 路径、模式或隔离失败
     */
    public synchronized WorkspaceSearchPage searchPage(
            String path,
            String literal,
            String glob,
            boolean caseSensitive,
            int maxMatches,
            int maxBytes,
            CancellationToken cancellation)
            throws Exception {
        requirePathPermission(path, true, false);
        WorkspaceFileProtocol.limit(maxMatches, MAX_ENTRIES, "maxMatches");
        WorkspaceFileProtocol.limit(maxBytes, MAX_BYTES, "maxBytes");
        if (literal == null || literal.isEmpty() || literal.length() > 4096) {
            throw new IllegalArgumentException("search literal must contain 1 to 4096 characters");
        }
        try (DataInputStream input = invoke("search", false, cancellation, output -> {
            output.writeUTF(path);
            output.writeUTF(literal);
            output.writeUTF(glob);
            output.writeBoolean(caseSensitive);
            output.writeInt(maxMatches);
            output.writeInt(maxBytes);
        })) {
            List<Match> matches = WorkspaceFileProtocol.readMatches(input);
            return new WorkspaceSearchPage(matches, input.readBoolean(), input.readLong());
        }
    }

    /**
     * 扫描普通文件的完整摘要，预算和未覆盖范围通过结果显式报告。
     *
     * @param path 根内相对目录，空或点表示根
     * @param maxEntries 全部访问条目上限，1 至 10000，包含目录
     * @param maxTotalBytes 实际累计读取上限，1 至 64 MiB
     * @param excludedDirs 任意深度排除的单个目录名；固定包含 .git
     * @param cancellation 取消令牌，取消会终止 Worker，不返回完整性声明
     * @return 完整文件 SHA-256、实际读取量、截断状态和实际排除路径
     * @throws Exception 起点越权、原生隔离、取消或协议失败
     */
    public synchronized WorkspaceInventory inventory(
            String path, int maxEntries, int maxTotalBytes, List<String> excludedDirs, CancellationToken cancellation)
            throws Exception {
        requirePathPermission(path, true, false);
        WorkspaceFileProtocol.limit(maxEntries, MAX_ENTRIES, "maxEntries");
        WorkspaceFileProtocol.limit(maxTotalBytes, MAX_BYTES, "maxTotalBytes");
        List<String> excluded = WorkspaceInventoryScanner.exclusions(excludedDirs);
        try (DataInputStream input = invoke("inventory", false, cancellation, output -> {
            output.writeUTF(path);
            output.writeInt(maxEntries);
            output.writeInt(maxTotalBytes);
            WorkspaceFileProtocol.writeStrings(output, excluded);
        })) {
            return WorkspaceFileProtocol.readInventory(input);
        }
    }

    /**
     * 校验整组编辑并取得修改前后快照，不执行写入。
     *
     * @param edits 不重复的相对文件编辑，最多 200 项，允许映射 100 个移动操作
     * @param maxTotalBytes 全部 before 与 after 内容的合计上限
     * @param cancellation 取消令牌
     * @return 调用方必须先持久化的恢复描述
     * @throws Exception expected SHA-256 冲突、路径或限额失败
     */
    public synchronized PreparedPatch preparePatch(List<Edit> edits, int maxTotalBytes, CancellationToken cancellation)
            throws Exception {
        for (Edit edit : edits) {
            requirePathPermission(edit.path(), false, true);
            requireDeletePermission(edit.content().isEmpty());
        }
        WorkspaceFileProtocol.limit(maxTotalBytes, MAX_BYTES, "maxTotalBytes");
        try (DataInputStream input = invoke("prepare", false, cancellation, output -> {
            WorkspaceFileProtocol.writeEdits(output, edits);
            output.writeInt(maxTotalBytes);
        })) {
            return WorkspaceFileProtocol.readPatch(input);
        }
    }

    /**
     * 重新核对全部 before 后逐文件应用；POSIX 修改原子交换，Windows 修改存在短暂缺失窗口。
     *
     * @param patch 已持久化且由本 Workspace prepare 得到的快照
     * @param cancellation 取消令牌，执行中取消后的恢复由调用方核对快照
     * @return APPLIED、ROLLED_BACK 或 RECOVERY_REQUIRED
     * @throws Exception Worker 被取消或输出丢失等未知结果，调用方必须进入恢复流程
     */
    public synchronized PatchResult applyPrepared(PreparedPatch patch, CancellationToken cancellation)
            throws Exception {
        for (Change change : patch.changes()) {
            requirePathPermission(change.before().path(), false, true);
            requireDeletePermission(!change.after().exists());
        }
        try (DataInputStream input =
                invoke("apply", true, cancellation, output -> WorkspaceFileProtocol.writePatch(output, patch))) {
            return new PatchResult(
                    Status.valueOf(input.readUTF()), input.readUTF(), WorkspaceFileProtocol.readStrings(input, 200));
        }
    }

    /**
     * 仅当当前内容仍匹配 after 时恢复 before，保留其后的用户修改。
     *
     * @param patch 原先已应用的补丁快照
     * @param cancellation 取消令牌
     * @return 有条件恢复结果
     * @throws Exception 隔离或未知 IO 结果
     */
    public synchronized PatchResult restore(PreparedPatch patch, CancellationToken cancellation) throws Exception {
        return applyPrepared(
                new PreparedPatch(patch.changes().stream()
                        .map(change -> new Change(change.after(), change.before()))
                        .toList()),
                cancellation);
    }

    private DataInputStream invoke(String operation, boolean write, CancellationToken cancellation, Encoder encoder)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(operation);
            encoder.write(output);
        }
        SandboxJavaRuntime runtime = SandboxJavaRuntime.forWorker(WorkspaceFileWorker.class);
        PermissionProfile permission = permission(runtime.executable(), write);
        return WorkspaceFileWorkerClient.invoke(
                root, permission, runtime, operation, bytes.toByteArray(), cancellation);
    }

    private PermissionProfile permission(Path java, boolean write) throws IOException {
        FilePermission files = effectivePermission
                .map(PermissionProfile::files)
                .orElseGet(() -> new FilePermission(List.of(root), List.of(root), true, false));
        ResourceLimits limits = effectivePermission
                .map(PermissionProfile::resources)
                .orElseGet(() -> new ResourceLimits(512L * 1024 * 1024, MAX_BYTES + 1024L * 1024, 4, 128));
        Duration timeout = effectivePermission
                .map(value -> value.processes().maxRunTime())
                .filter(value -> value.compareTo(TIMEOUT) < 0)
                .orElse(TIMEOUT);
        List<Path> reads = scopedRoots(Stream.concat(files.readRoots().stream(), files.writeRoots().stream())
                .toList());
        List<Path> writes = write ? scopedRoots(files.writeRoots()) : List.of();
        if (write && writes.isEmpty()) {
            throw new SecurityException("Workspace file operation has no effective write roots");
        }
        return new PermissionProfile(
                "workspace-file-worker",
                1,
                new FilePermission(reads, writes, write && files.allowDelete(), false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(java.getFileName().toString()), false, timeout),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.NONE),
                limits);
    }

    private void requirePathPermission(String path, boolean allowRoot, boolean write) throws IOException {
        WorkspaceFileProtocol.requireRelative(path, allowRoot);
        WorkspaceFileMetadata.requireVisible(path);
        if (effectivePermission.isEmpty()) {
            return;
        }
        FilePermission files = effectivePermission.orElseThrow().files();
        List<Path> permitted = write
                ? scopedRoots(files.writeRoots())
                : scopedRoots(Stream.concat(files.readRoots().stream(), files.writeRoots().stream())
                        .toList());
        Path resolved = root.resolve(path).normalize();
        if (permitted.stream().noneMatch(resolved::startsWith)) {
            throw new SecurityException("Workspace path is outside the effective file permission");
        }
    }

    private void requireDeletePermission(boolean delete) {
        if (delete
                && effectivePermission
                        .filter(value -> !value.files().allowDelete())
                        .isPresent()) {
            throw new SecurityException("Workspace file deletion is not permitted");
        }
    }

    private List<Path> scopedRoots(List<Path> permitted) throws IOException {
        ArrayList<Path> scoped = new ArrayList<>();
        for (Path path : permitted) {
            Path real = path.toRealPath();
            if (!real.equals(path)) {
                throw new IOException("Frozen Workspace permission root changed into a link or alias");
            }
            if (root.startsWith(real)) {
                scoped.add(root);
            } else if (real.startsWith(root)) {
                scoped.add(real);
            }
        }
        return scoped.stream().distinct().toList();
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }

    /**
     * 普通文件快照。
     *
     * @param path 根内相对路径
     * @param exists 是否存在；不存在时 sha256 与 content 必须为空
     * @param sha256 存在文件的小写 SHA-256，不可空
     * @param content 原始字节，防御性复制，不可空
     */
    public record Snapshot(String path, boolean exists, String sha256, byte[] content) {
        /** 复制内容并校验摘要，拒绝伪造或不一致的恢复快照。 */
        public Snapshot {
            WorkspaceFileProtocol.requireRelative(path, false);
            content = Objects.requireNonNull(content, "content").clone();
            Objects.requireNonNull(sha256, "sha256");
            if (content.length > MAX_BYTES
                    || (exists ? !hash(content).equals(sha256) : !sha256.isEmpty() || content.length != 0)) {
                throw new IllegalArgumentException("invalid file snapshot digest or size");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /**
     * 明确的文件创建、替换或删除请求。
     *
     * @param path 根内相对路径
     * @param expectedSha256 已有内容摘要；空表示必须不存在
     * @param content 新内容；空表示删除，数组被复制
     */
    public record Edit(String path, Optional<String> expectedSha256, Optional<byte[]> content) {
        /** 校验路径、摘要及大小，不接受无 expected 的覆盖写。 */
        public Edit {
            WorkspaceFileProtocol.requireRelative(path, false);
            expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256");
            content = Objects.requireNonNull(content, "content").map(byte[]::clone);
            if (expectedSha256.filter(value -> !value.matches("[a-f0-9]{64}")).isPresent()
                    || content.filter(value -> value.length > MAX_BYTES).isPresent()
                    || expectedSha256.isEmpty() && content.isEmpty()) {
                throw new IllegalArgumentException("invalid file edit digest, size or deletion");
            }
        }

        @Override
        public Optional<byte[]> content() {
            return content.map(byte[]::clone);
        }
    }

    /**
     * @param before 修改前快照，不可空
     * @param after 同路径修改后快照，不可空
     */
    public record Change(Snapshot before, Snapshot after) {
        /** 拒绝跨路径变化；重命名应明确建模为删除与创建。 */
        public Change {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            if (!before.path().equals(after.path())) {
                throw new IllegalArgumentException("change snapshots must have the same path");
            }
        }
    }

    /** @param changes 不可空且不重复的文件变化，1 至 200 项 */
    public record PreparedPatch(List<Change> changes) {
        /** 复制并约束补丁大小及路径唯一性。 */
        public PreparedPatch {
            changes = List.copyOf(changes);
            if (changes.isEmpty()
                    || changes.size() > 200
                    || changes.stream()
                                    .map(change -> change.before().path())
                                    .distinct()
                                    .count()
                            != changes.size()) {
                throw new IllegalArgumentException("patch must contain 1 to 200 distinct paths");
            }
            long bytes = changes.stream()
                    .mapToLong(change -> change.before().content().length
                            + (long) change.after().content().length)
                    .sum();
            if (bytes > MAX_BYTES) {
                throw new IllegalArgumentException("patch snapshots exceed 64 MiB");
            }
        }
    }

    /**
     * @param path 根内相对路径
     * @param directory 是否普通目录
     * @param size 文件字节数，目录为零
     */
    public record Entry(String path, boolean directory, long size) {}

    /**
     * @param path 根内相对路径
     * @param line 一起始行号
     * @param text 有界匹配行，不可空
     */
    public record Match(String path, int line, String text) {}

    /** 补丁应用及有条件恢复的结果分类。 */
    public enum Status {
        /** 全部文件已应用。 */
        APPLIED,
        /** 未写入或已恢复所有本次写入。 */
        ROLLED_BACK,
        /** 存在无法确认或无法恢复的文件，须锁定 Workspace 并核对。 */
        RECOVERY_REQUIRED
    }

    /**
     * @param status 补丁状态，不可空
     * @param detail 适合事实记录的有界说明，不可空
     * @param recoveryPaths 保留实际 inode 的恢复目录，规范相对路径，最多 200 项，不可空
     */
    public record PatchResult(Status status, String detail, List<String> recoveryPaths) {
        /** 校验并复制结果，恢复路径不得由模型指定。 */
        public PatchResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            recoveryPaths = List.copyOf(recoveryPaths);
            if (recoveryPaths.size() > 200) {
                throw new IllegalArgumentException("too many recovery paths");
            }
            recoveryPaths.forEach(path -> WorkspaceFileProtocol.requireRelative(path, false));
        }

        /** 构造未产生恢复目录的兼容结果。 */
        public PatchResult(Status status, String detail) {
            this(status, detail, List.of());
        }
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
