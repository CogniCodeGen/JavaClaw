package com.javaclaw.nativehost.coding;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;

/** 固定 Workspace 文件 Worker 的目录能力；文件权限与沙箱配置复用文件端口。 调用者须持有执行根租约并在进入写 Worker 前持久化意图；取消后结果未知，禁止自动重放。 */
public final class WorkspaceDirectoryFiles {
    private final WorkspaceFileAccess files;

    /**
     * @param root 服务端冻结的真实根
     * @param permission 本次调用有效权限
     * @throws IOException 根不可用
     */
    public WorkspaceDirectoryFiles(Path root, PermissionProfile permission) throws IOException {
        files = new WorkspaceFileAccess(root, permission);
    }

    /**
     * @param path 非根相对目录
     * @param parents 是否创建缺失父目录
     * @param cancellation 当前调用取消令牌
     * @return 包含真实部分创建的回执
     * @throws Exception 路径、权限、沙箱或结果未知
     */
    public WorkspaceDirectoryResult mkdir(String path, boolean parents, CancellationToken cancellation)
            throws Exception {
        requirePermission(path, false);
        try (DataInputStream input = files.invoke("mkdir", true, cancellation, output -> {
            output.writeUTF(path);
            output.writeBoolean(parents);
        })) {
            return read(input);
        }
    }

    /**
     * @param path 非根空目录
     * @param cancellation 当前调用取消令牌
     * @return 保留被移走目录原对象的回执
     * @throws Exception 删除权限、路径、沙箱或结果未知
     */
    public WorkspaceDirectoryResult rmdir(String path, CancellationToken cancellation) throws Exception {
        requirePermission(path, true);
        try (DataInputStream input = files.invoke("rmdir", true, cancellation, output -> output.writeUTF(path))) {
            return read(input);
        }
    }

    /**
     * 写 Worker 启动前校验本次目录调用权限，实际执行时再次校验。
     *
     * @param path 非根相对目录
     * @param delete 是否删除目录及保存原对象
     * @throws IOException 冻结权限根不可用
     */
    public void requirePermission(String path, boolean delete) throws IOException {
        files.requirePathPermission(path, false, true);
        if (delete) {
            files.requirePathPermission(WorkspaceFileTree.parent(path), true, true);
            files.requireDeletePermission(true);
        }
    }

    /**
     * 应用普通文件字节补丁；提交时也禁止自动创建父目录。
     *
     * @param patch 服务端已持久化的字节快照
     * @param cancellation 本次取消令牌
     * @return 包含部分成功与恢复路径的原生回执
     * @throws Exception 权限、沙箱或副作用结果未知
     */
    public WorkspaceFileAccess.PatchResult applyFiles(
            WorkspaceFileAccess.PreparedPatch patch, CancellationToken cancellation) throws Exception {
        for (var change : patch.changes()) {
            files.requirePathPermission(change.before().path(), false, true);
            files.requireDeletePermission(!change.after().exists());
        }
        try (DataInputStream input = files.invoke(
                "apply-existing-parents",
                true,
                cancellation,
                output -> WorkspaceFileProtocol.writePatch(output, patch))) {
            return new WorkspaceFileAccess.PatchResult(
                    WorkspaceFileAccess.Status.valueOf(input.readUTF()),
                    input.readUTF(),
                    WorkspaceFileProtocol.readStrings(input, 200),
                    WorkspaceFileProtocol.readStrings(input, 200));
        }
    }

    static void write(DataOutputStream output, WorkspaceDirectoryResult result) throws IOException {
        output.writeInt(result.changes().size());
        for (var change : result.changes()) {
            output.writeUTF(change.path());
            output.writeUTF(change.operation());
        }
        output.writeUTF(result.failureCode().orElse(""));
        WorkspaceFileProtocol.writeStrings(output, result.recoveryPaths());
    }

    private static WorkspaceDirectoryResult read(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 200) {
            throw new IOException("directory change limit exceeded");
        }
        var changes = new ArrayList<WorkspaceDirectoryResult.Change>();
        for (int index = 0; index < count; index++) {
            changes.add(new WorkspaceDirectoryResult.Change(input.readUTF(), input.readUTF()));
        }
        String failure = input.readUTF();
        return new WorkspaceDirectoryResult(
                changes,
                failure.isEmpty() ? Optional.empty() : Optional.of(failure),
                WorkspaceFileProtocol.readStrings(input, 200));
    }
}
