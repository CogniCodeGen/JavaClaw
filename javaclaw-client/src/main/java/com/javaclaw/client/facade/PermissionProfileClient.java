package com.javaclaw.client.facade;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.PermissionProfileRpcContracts;

/** PermissionProfile Core 方法的强类型 facade。 */
public final class PermissionProfileClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public PermissionProfileClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 列出每个权限配置的最新不可变版本。
     *
     * @return 权限配置
     */
    public List<PermissionProfile> list() {
        return connection
                .query("permissionProfile/list", Map.of(), PermissionProfileRpcContracts.ListResult.class)
                .profiles();
    }

    /**
     * 读取精确不可变版本。
     *
     * @param reference 配置引用
     * @return 权限配置
     */
    public PermissionProfile read(PermissionProfileRef reference) {
        return connection.query(
                "permissionProfile/read",
                new PermissionProfileRpcContracts.ReadPayload(reference),
                PermissionProfile.class);
    }

    /**
     * 读取一个配置的全部历史。
     *
     * @param id 配置标识
     * @return revision 升序历史
     */
    public List<PermissionProfile> history(String id) {
        return connection
                .query(
                        "permissionProfile/history",
                        new PermissionProfileRpcContracts.HistoryPayload(id),
                        PermissionProfileRpcContracts.HistoryResult.class)
                .profiles();
    }

    /**
     * 从精确源版本克隆用户配置。
     *
     * @param source 精确源版本
     * @param newId 新配置标识
     * @param options 幂等键，expected revision 必须为 0
     * @return 新配置 revision 1
     */
    public PermissionProfile cloneProfile(PermissionProfileRef source, String newId, CommandOptions options) {
        return connection.command(
                "permissionProfile/clone",
                new PermissionProfileRpcContracts.ClonePayload(source, newId),
                options,
                PermissionProfile.class);
    }

    /**
     * 写入权限配置的新版本。
     *
     * @param profile 完整新版本
     * @param options 幂等键与上一版本号
     * @return 已保存版本
     */
    public PermissionProfile update(PermissionProfile profile, CommandOptions options) {
        return connection.command(
                "permissionProfile/update",
                new PermissionProfileRpcContracts.UpdatePayload(profile),
                options,
                PermissionProfile.class);
    }

    /**
     * 比较同一配置的两个 revision。
     *
     * @param id 配置标识
     * @param beforeVersion 基准版本
     * @param afterVersion 比较版本
     * @return 分区差异
     */
    public PermissionProfileDiff diff(String id, long beforeVersion, long afterVersion) {
        return connection.query(
                "permissionProfile/diff",
                new PermissionProfileRpcContracts.DiffPayload(id, beforeVersion, afterVersion),
                PermissionProfileDiff.class);
    }

    /**
     * 预览五层权限交集和拒绝原因。
     *
     * @param workspaceId Workspace
     * @param profile 冻结 Profile 引用
     * @param turnGrant 可选 Turn grant
     * @param toolDeclaration 可选工具声明
     * @return 权威有效权限预览
     */
    public EffectivePermissionPreview effectivePreview(
            WorkspaceId workspaceId,
            PermissionProfileRef profile,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration) {
        return connection.query(
                "permissionProfile/effectivePreview",
                new PermissionProfileRpcContracts.EffectivePreviewPayload(
                        workspaceId, profile, turnGrant, toolDeclaration),
                EffectivePermissionPreview.class);
    }
}
