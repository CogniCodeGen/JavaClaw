package com.javaclaw.client.facade;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.BundleRpcContracts;

/** 第三方 Bundle、Trust Key 与可恢复 Trash 的强类型 SDK facade。 */
public final class ExtensionBundleClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public ExtensionBundleClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** @return 已安装 Bundle 管理详情 */
    public List<BundleRpcContracts.Bundle> list() {
        return connection
                .query("extension/bundle/list", EmptyPayload.INSTANCE, BundleRpcContracts.BundleListResult.class)
                .bundles();
    }

    /** @param extensionId 扩展标识 @return Bundle 管理详情 */
    public BundleRpcContracts.Bundle read(String extensionId) {
        return connection
                .query(
                        "extension/bundle/read",
                        new BundleRpcContracts.BundlePayload(extensionId),
                        BundleRpcContracts.BundleResult.class)
                .bundle();
    }

    /**
     * 从已上传 Core Attachment 验签并建立 staging。
     *
     * @param attachment 媒体类型必须为 JavaClaw Bundle ZIP
     * @param options expected revision 必须为 0
     * @return 权限和签名审阅结果
     */
    public BundleRpcContracts.StageResult stage(AttachmentMetadata attachment, CommandOptions options) {
        Objects.requireNonNull(attachment, "attachment");
        var pointer = new BundleRpcContracts.AttachmentPointer(attachment.digest(), attachment.digest());
        return connection.command(
                "extension/bundle/stage",
                new BundleRpcContracts.StagePayload(pointer),
                options,
                BundleRpcContracts.StageResult.class);
    }

    /**
     * 安装用户已经核对 manifest 摘要和权限的 Bundle。
     *
     * @param staging staging 审阅结果
     * @param options expected revision 必须为 0
     * @return 初始为 INSTALLED 的 Bundle
     */
    public BundleRpcContracts.Bundle install(BundleRpcContracts.StageResult staging, CommandOptions options) {
        return commit("extension/bundle/install", staging, options);
    }

    /**
     * 健康检查通过后原子升级同一扩展。
     *
     * @param staging 新 Bundle staging；extensionId 必须与现有 Bundle 一致
     * @param options expected revision 为当前 Bundle revision
     * @return 新 revision Bundle
     */
    public BundleRpcContracts.Bundle upgrade(BundleRpcContracts.StageResult staging, CommandOptions options) {
        return commit("extension/bundle/upgrade", staging, options);
    }

    /** @param extensionId 扩展标识 @param options 当前 revision @return 健康快照更新后的 Bundle */
    public BundleRpcContracts.Bundle probe(String extensionId, CommandOptions options) {
        return lifecycle("extension/bundle/health/probe", extensionId, options);
    }

    /** @param extensionId 扩展标识 @param options 当前 revision @return ENABLED Bundle */
    public BundleRpcContracts.Bundle enable(String extensionId, CommandOptions options) {
        return lifecycle("extension/bundle/enable", extensionId, options);
    }

    /** @param extensionId 扩展标识 @param options 当前 revision @return DISABLED Bundle */
    public BundleRpcContracts.Bundle disable(String extensionId, CommandOptions options) {
        return lifecycle("extension/bundle/disable", extensionId, options);
    }

    /**
     * 将已禁用 Bundle 移入可恢复 Trash。
     *
     * @param extensionId 扩展标识
     * @param options 当前 revision
     * @return TRASHED 条目
     */
    public BundleRpcContracts.TrashEntry uninstall(String extensionId, CommandOptions options) {
        return connection
                .command(
                        "extension/bundle/uninstall",
                        new BundleRpcContracts.BundlePayload(extensionId),
                        options,
                        BundleRpcContracts.TrashResult.class)
                .entry();
    }

    /** @return Trash 历史 */
    public List<BundleRpcContracts.TrashEntry> listTrash() {
        return connection
                .query("extension/bundle/trash/list", EmptyPayload.INSTANCE, BundleRpcContracts.TrashListResult.class)
                .entries();
    }

    /** @param trashId Trash 标识 @return Trash 条目 */
    public BundleRpcContracts.TrashEntry readTrash(String trashId) {
        return connection
                .query(
                        "extension/bundle/trash/read",
                        new BundleRpcContracts.TrashPayload(trashId),
                        BundleRpcContracts.TrashResult.class)
                .entry();
    }

    /** @param trashId Trash 标识 @param options 被卸载 revision @return 恢复后的 DISABLED Bundle */
    public BundleRpcContracts.Bundle restoreTrash(String trashId, CommandOptions options) {
        return connection
                .command(
                        "extension/bundle/trash/restore",
                        new BundleRpcContracts.TrashPayload(trashId),
                        options,
                        BundleRpcContracts.BundleResult.class)
                .bundle();
    }

    /**
     * 永久清除 Trash 文件。
     *
     * @param trashId Trash 标识
     * @param confirmation 必须精确等于 {@code PURGE <trashId>}
     * @param options 被卸载 revision
     * @return PURGED tombstone
     */
    public BundleRpcContracts.TrashEntry purgeTrash(String trashId, String confirmation, CommandOptions options) {
        return connection
                .command(
                        "extension/bundle/trash/purge",
                        new BundleRpcContracts.TrashPurgePayload(trashId, confirmation),
                        options,
                        BundleRpcContracts.TrashResult.class)
                .entry();
    }

    /** @return ACTIVE 与 REVOKED Trust Key */
    public List<BundleRpcContracts.TrustKey> listTrustKeys() {
        return connection
                .query("extension/trustKey/list", EmptyPayload.INSTANCE, BundleRpcContracts.TrustKeyListResult.class)
                .keys();
    }

    /** @param keyId 密钥标识 @return Trust Key */
    public BundleRpcContracts.TrustKey readTrustKey(String keyId) {
        return connection
                .query(
                        "extension/trustKey/read",
                        new BundleRpcContracts.TrustKeyPayload(keyId),
                        BundleRpcContracts.TrustKeyResult.class)
                .key();
    }

    /**
     * 从 Core Attachment 导入 Ed25519 Trust Key。
     *
     * @param keyId manifest 使用的 signingKeyId
     * @param attachment DER 或 Base64 DER 公钥 Attachment
     * @param options expected revision 必须为 0
     * @return ACTIVE Trust Key
     */
    public BundleRpcContracts.TrustKey importTrustKey(
            String keyId, AttachmentMetadata attachment, CommandOptions options) {
        Objects.requireNonNull(attachment, "attachment");
        var pointer = new BundleRpcContracts.AttachmentPointer(attachment.digest(), attachment.digest());
        return connection
                .command(
                        "extension/trustKey/import",
                        new BundleRpcContracts.TrustKeyImportPayload(keyId, pointer),
                        options,
                        BundleRpcContracts.TrustKeyResult.class)
                .key();
    }

    /** @param keyId 密钥标识 @param options 当前 key revision @return REVOKED Trust Key */
    public BundleRpcContracts.TrustKey revokeTrustKey(String keyId, CommandOptions options) {
        return connection
                .command(
                        "extension/trustKey/revoke",
                        new BundleRpcContracts.TrustKeyPayload(keyId),
                        options,
                        BundleRpcContracts.TrustKeyResult.class)
                .key();
    }

    private BundleRpcContracts.Bundle commit(
            String method, BundleRpcContracts.StageResult staging, CommandOptions options) {
        Objects.requireNonNull(staging, "staging");
        return connection
                .command(
                        method,
                        new BundleRpcContracts.CommitPayload(staging.stagingId(), staging.manifestDigest()),
                        options,
                        BundleRpcContracts.BundleResult.class)
                .bundle();
    }

    private BundleRpcContracts.Bundle lifecycle(String method, String extensionId, CommandOptions options) {
        return connection
                .command(
                        method,
                        new BundleRpcContracts.BundlePayload(extensionId),
                        options,
                        BundleRpcContracts.BundleResult.class)
                .bundle();
    }

    private record EmptyPayload() {
        private static final EmptyPayload INSTANCE = new EmptyPayload();
    }
}
