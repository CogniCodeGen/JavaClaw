package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

import com.javaclaw.protocol.BundleRpcContracts;

/** 第三方 Bundle、Trust Key 与 Trash 管理页使用的异步 SDK 边界。 */
public interface BundleSettingsGateway {
    /** @return 已安装 Bundle 权威目录 */
    CompletionStage<List<BundleRpcContracts.Bundle>> bundles();

    /**
     * 上传并验签用户选择的 Bundle 文件。
     *
     * @param archive 本机 ZIP 文件
     * @return staging 权限审阅
     */
    CompletionStage<BundleRpcContracts.StageResult> stageBundle(Path archive);

    /** @param staging 已确认 staging @return 新安装 Bundle */
    CompletionStage<BundleRpcContracts.Bundle> installBundle(BundleRpcContracts.StageResult staging);

    /** @param staging 已确认 staging @param current 当前 Bundle @return 原子升级后的 Bundle */
    CompletionStage<BundleRpcContracts.Bundle> upgradeBundle(
            BundleRpcContracts.StageResult staging, BundleRpcContracts.Bundle current);

    /** @param bundle 当前 Bundle @return 探测后的权威状态 */
    CompletionStage<BundleRpcContracts.Bundle> probeBundle(BundleRpcContracts.Bundle bundle);

    /** @param bundle 当前 Bundle @param enabled 是否启用 @return 更新后的权威状态 */
    CompletionStage<BundleRpcContracts.Bundle> setBundleEnabled(BundleRpcContracts.Bundle bundle, boolean enabled);

    /** @param bundle 当前 Bundle @return 可恢复 Trash 条目 */
    CompletionStage<BundleRpcContracts.TrashEntry> uninstallBundle(BundleRpcContracts.Bundle bundle);

    /** @return ACTIVE 与 REVOKED Trust Key */
    CompletionStage<List<BundleRpcContracts.TrustKey>> trustKeys();

    /**
     * 上传并解析待导入公钥；只返回可展示指纹，不保存原始字节。
     *
     * @param publicKey 本机 DER 或 Base64 DER 文件
     * @return 指纹确认草稿
     */
    CompletionStage<TrustKeyImportDraft> prepareTrustKey(Path publicKey);

    /** @param keyId manifest 使用的密钥标识 @param draft 已核对指纹草稿 @return 导入结果 */
    CompletionStage<BundleRpcContracts.TrustKey> importTrustKey(String keyId, TrustKeyImportDraft draft);

    /** @param key 当前密钥 @return REVOKED 状态 */
    CompletionStage<BundleRpcContracts.TrustKey> revokeTrustKey(BundleRpcContracts.TrustKey key);

    /** @return Bundle Trash 历史 */
    CompletionStage<List<BundleRpcContracts.TrashEntry>> bundleTrash();

    /** @param entry TRASHED 条目 @return 新 revision 的 DISABLED Bundle */
    CompletionStage<BundleRpcContracts.Bundle> restoreBundle(BundleRpcContracts.TrashEntry entry);

    /** @param entry TRASHED 条目 @param confirmation 精确危险确认 @return PURGED tombstone */
    CompletionStage<BundleRpcContracts.TrashEntry> purgeBundle(
            BundleRpcContracts.TrashEntry entry, String confirmation);
}
