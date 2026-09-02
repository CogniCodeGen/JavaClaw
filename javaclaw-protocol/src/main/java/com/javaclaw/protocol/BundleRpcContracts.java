package com.javaclaw.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Protocol v2 第三方 Bundle、Trust Key 与 Trash 的强类型 wire 契约。 */
public final class BundleRpcContracts {
    /** Bundle ZIP 的唯一允许媒体类型。 */
    public static final String BUNDLE_MEDIA_TYPE = "application/vnd.javaclaw.bundle+zip";

    /** Ed25519 公钥附件的唯一允许媒体类型。 */
    public static final String PUBLIC_KEY_MEDIA_TYPE = "application/vnd.javaclaw.ed25519-public-key";

    private BundleRpcContracts() {}

    /**
     * Core Attachment 的内容寻址引用。
     *
     * <p>5.0 的 Attachment ID 就是其 SHA-256；同时携带两者是为了让调用端显式确认所选附件与已审阅摘要一致。
     *
     * @param attachmentId Core Attachment ID
     * @param digest 客户端确认的 SHA-256
     */
    public record AttachmentPointer(String attachmentId, String digest) {
        /** 校验内容寻址身份没有分叉。 */
        public AttachmentPointer {
            attachmentId = BundleRpcContracts.digest(attachmentId, "attachmentId");
            digest = BundleRpcContracts.digest(digest, "digest");
            if (!attachmentId.equals(digest)) {
                throw new IllegalArgumentException("attachmentId must equal digest");
            }
        }
    }

    /**
     * Bundle staging 参数。
     *
     * @param attachment 已上传的 ZIP Attachment
     */
    public record StagePayload(AttachmentPointer attachment) {
        /** 校验附件引用。 */
        public StagePayload {
            Objects.requireNonNull(attachment, "attachment");
        }
    }

    /**
     * 第三方进程权限审阅内容。
     *
     * @param workspaceRead 是否请求读取 Workspace
     * @param workspaceWrite 是否请求写入 Workspace
     * @param allowDelete 是否请求删除 Workspace 内容
     * @param networkHosts 仅可经 Network Broker 访问的主机
     * @param networkPorts 仅可经 Network Broker 访问的端口
     * @param tlsOnly 是否只允许 TLS
     * @param executableName 唯一可启动的 Bundle 可执行文件名
     * @param maxRunTime 单次调用时限
     * @param memoryBytes 进程树内存上限
     * @param outputBytes 单次输入输出上限
     * @param childProcesses 子进程上限
     * @param openFiles 打开文件上限
     */
    public record PermissionReview(
            boolean workspaceRead,
            boolean workspaceWrite,
            boolean allowDelete,
            Set<String> networkHosts,
            Set<Integer> networkPorts,
            boolean tlsOnly,
            String executableName,
            Duration maxRunTime,
            long memoryBytes,
            long outputBytes,
            int childProcesses,
            int openFiles) {
        /** 固定集合并校验资源值。 */
        public PermissionReview {
            networkHosts = Set.copyOf(networkHosts);
            networkPorts = Set.copyOf(networkPorts);
            executableName = text(executableName, "executableName");
            Objects.requireNonNull(maxRunTime, "maxRunTime");
            if (maxRunTime.isNegative()
                    || maxRunTime.isZero()
                    || memoryBytes < 1
                    || outputBytes < 1
                    || childProcesses < 1
                    || openFiles < 1) {
                throw new IllegalArgumentException("permission review limits must be positive");
            }
            if (workspaceWrite && !workspaceRead) {
                throw new IllegalArgumentException("workspaceWrite requires workspaceRead");
            }
            if (allowDelete && !workspaceWrite) {
                throw new IllegalArgumentException("allowDelete requires workspaceWrite");
            }
        }
    }

    /**
     * 已验证 staging 的审阅结果。
     *
     * @param stagingId staging 内容摘要
     * @param attachment 来源 Attachment
     * @param manifestDigest 已签名规范 manifest 摘要
     * @param extensionId 扩展标识
     * @param displayName 展示名称
     * @param version Bundle 版本
     * @param signingKeyId 签名公钥标识
     * @param signingKeyFingerprint 签名公钥 SHA-256 指纹
     * @param contributionKinds 贡献点类别
     * @param permissions 待确认权限
     */
    public record StageResult(
            String stagingId,
            AttachmentPointer attachment,
            String manifestDigest,
            String extensionId,
            String displayName,
            String version,
            String signingKeyId,
            String signingKeyFingerprint,
            Set<String> contributionKinds,
            PermissionReview permissions) {
        /** 校验审阅结果。 */
        public StageResult {
            stagingId = digest(stagingId, "stagingId");
            Objects.requireNonNull(attachment, "attachment");
            manifestDigest = BundleRpcContracts.digest(manifestDigest, "manifestDigest");
            extensionId = text(extensionId, "extensionId");
            displayName = text(displayName, "displayName");
            version = text(version, "version");
            signingKeyId = text(signingKeyId, "signingKeyId");
            signingKeyFingerprint = BundleRpcContracts.digest(signingKeyFingerprint, "signingKeyFingerprint");
            contributionKinds = Set.copyOf(contributionKinds);
            Objects.requireNonNull(permissions, "permissions");
        }
    }

    /**
     * 安装或升级已经审阅的 staging Bundle。
     *
     * @param stagingId staging 标识
     * @param approvedManifestDigest 用户确认的 manifest 摘要
     */
    public record CommitPayload(String stagingId, String approvedManifestDigest) {
        /** 校验 staging 身份。 */
        public CommitPayload {
            stagingId = digest(stagingId, "stagingId");
            approvedManifestDigest = digest(approvedManifestDigest, "approvedManifestDigest");
        }
    }

    /**
     * Bundle 资源标识。
     *
     * @param extensionId 第三方扩展标识
     */
    public record BundlePayload(String extensionId) {
        /** 校验扩展标识。 */
        public BundlePayload {
            extensionId = text(extensionId, "extensionId");
        }
    }

    /** Bundle 健康状态。 */
    public enum HealthState {
        /** 尚未执行健康检查。 */
        NOT_PROBED,
        /** 最近一次健康检查成功。 */
        HEALTHY,
        /** 已禁用，不执行探测。 */
        DISABLED,
        /** 失败后等待重试窗口。 */
        BACKING_OFF,
        /** 达到失败阈值或安全校验失败，已隔离。 */
        QUARANTINED
    }

    /**
     * Bundle 健康、失败与退避快照。
     *
     * @param state 健康状态
     * @param failureCount 连续失败次数
     * @param checkedAt 最近探测时间
     * @param nextRetryAt 最早允许重试时间
     * @param lastFailure 最近一次脱敏失败说明
     */
    public record Health(
            HealthState state,
            int failureCount,
            Optional<Instant> checkedAt,
            Optional<Instant> nextRetryAt,
            Optional<String> lastFailure) {
        /** 校验健康快照。 */
        public Health {
            Objects.requireNonNull(state, "state");
            if (failureCount < 0) {
                throw new IllegalArgumentException("failureCount must not be negative");
            }
            checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
            nextRetryAt = Objects.requireNonNull(nextRetryAt, "nextRetryAt");
            lastFailure = Objects.requireNonNull(lastFailure, "lastFailure");
        }
    }

    /**
     * 第三方 Bundle 管理详情。
     *
     * @param id 扩展标识
     * @param displayName 展示名称
     * @param version Bundle 版本
     * @param revision Bundle revision
     * @param state 生命周期状态
     * @param manifestDigest 已签名 manifest 摘要
     * @param signingKeyId 签名密钥标识
     * @param signingKeyFingerprint 签名密钥指纹
     * @param contributionKinds 贡献点类别
     * @param permissions 已审阅权限
     * @param health 健康、失败与退避
     */
    public record Bundle(
            String id,
            String displayName,
            String version,
            long revision,
            String state,
            String manifestDigest,
            String signingKeyId,
            String signingKeyFingerprint,
            Set<String> contributionKinds,
            PermissionReview permissions,
            Health health) {
        /** 校验 Bundle 快照。 */
        public Bundle {
            id = text(id, "id");
            displayName = text(displayName, "displayName");
            version = text(version, "version");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            state = text(state, "state");
            manifestDigest = BundleRpcContracts.digest(manifestDigest, "manifestDigest");
            signingKeyId = text(signingKeyId, "signingKeyId");
            signingKeyFingerprint = BundleRpcContracts.digest(signingKeyFingerprint, "signingKeyFingerprint");
            contributionKinds = Set.copyOf(contributionKinds);
            Objects.requireNonNull(permissions, "permissions");
            Objects.requireNonNull(health, "health");
        }
    }

    /** @param bundles 按扩展标识排序的 Bundle */
    public record BundleListResult(List<Bundle> bundles) {
        /** 固定结果。 */
        public BundleListResult {
            bundles = List.copyOf(bundles);
        }
    }

    /** @param bundle Bundle 详情 */
    public record BundleResult(Bundle bundle) {
        /** 校验结果。 */
        public BundleResult {
            Objects.requireNonNull(bundle, "bundle");
        }
    }

    /** Trust Key 状态。 */
    public enum TrustState {
        /** 可以验证新 staging 和已安装 Bundle。 */
        ACTIVE,
        /** 已撤销，新调用与新安装立即拒绝。 */
        REVOKED
    }

    /**
     * 管理员信任的 Ed25519 公钥元数据。
     *
     * @param id 密钥标识
     * @param fingerprint DER 编码公钥的 SHA-256
     * @param attachmentDigest 导入来源 Attachment 摘要
     * @param revision 单调 revision
     * @param state 当前状态
     * @param createdAt 导入时间
     * @param updatedAt 最近更新时间
     */
    public record TrustKey(
            String id,
            String fingerprint,
            String attachmentDigest,
            long revision,
            TrustState state,
            Instant createdAt,
            Instant updatedAt) {
        /** 校验 Trust Key 元数据。 */
        public TrustKey {
            id = text(id, "id");
            fingerprint = BundleRpcContracts.digest(fingerprint, "fingerprint");
            attachmentDigest = BundleRpcContracts.digest(attachmentDigest, "attachmentDigest");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /** @param keys 按密钥标识排序的 Trust Key */
    public record TrustKeyListResult(List<TrustKey> keys) {
        /** 固定结果。 */
        public TrustKeyListResult {
            keys = List.copyOf(keys);
        }
    }

    /** @param key Trust Key 详情 */
    public record TrustKeyResult(TrustKey key) {
        /** 校验结果。 */
        public TrustKeyResult {
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * Trust Key 查询或撤销参数。
     *
     * @param keyId 密钥标识
     */
    public record TrustKeyPayload(String keyId) {
        /** 校验密钥标识。 */
        public TrustKeyPayload {
            keyId = BundleRpcContracts.keyId(keyId);
        }
    }

    /**
     * 从 Core Attachment 导入 Trust Key。
     *
     * @param keyId 密钥标识；必须与 Bundle manifest 的 signingKeyId 一致
     * @param attachment DER 或 Base64 DER Ed25519 公钥
     */
    public record TrustKeyImportPayload(String keyId, AttachmentPointer attachment) {
        /** 校验导入参数。 */
        public TrustKeyImportPayload {
            keyId = BundleRpcContracts.keyId(keyId);
            Objects.requireNonNull(attachment, "attachment");
        }
    }

    /** Trash 内容状态。 */
    public enum TrashState {
        /** 文件仍在 Trash，可恢复。 */
        TRASHED,
        /** 已恢复为新的 Bundle revision。 */
        RESTORED,
        /** 文件已永久清除。 */
        PURGED
    }

    /**
     * Trash 条目。
     *
     * @param trashId 不透明 Trash 标识
     * @param extensionId 扩展标识
     * @param version 被卸载版本
     * @param revision 被卸载 revision
     * @param manifestDigest manifest 摘要
     * @param signingKeyId 签名密钥
     * @param state Trash 状态
     * @param removedAt 卸载时间
     * @param restoredRevision 恢复后新 revision
     * @param purgedAt 永久清除时间
     */
    public record TrashEntry(
            String trashId,
            String extensionId,
            String version,
            long revision,
            String manifestDigest,
            String signingKeyId,
            TrashState state,
            Instant removedAt,
            Optional<Long> restoredRevision,
            Optional<Instant> purgedAt) {
        /** 校验 Trash 元数据。 */
        public TrashEntry {
            trashId = text(trashId, "trashId");
            extensionId = text(extensionId, "extensionId");
            version = text(version, "version");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            manifestDigest = BundleRpcContracts.digest(manifestDigest, "manifestDigest");
            signingKeyId = text(signingKeyId, "signingKeyId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(removedAt, "removedAt");
            restoredRevision = Objects.requireNonNull(restoredRevision, "restoredRevision");
            purgedAt = Objects.requireNonNull(purgedAt, "purgedAt");
        }
    }

    /** @param entries 按卸载时间倒序的 Trash 条目 */
    public record TrashListResult(List<TrashEntry> entries) {
        /** 固定结果。 */
        public TrashListResult {
            entries = List.copyOf(entries);
        }
    }

    /** @param entry Trash 详情 */
    public record TrashResult(TrashEntry entry) {
        /** 校验结果。 */
        public TrashResult {
            Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * Trash 查询、恢复或清除参数。
     *
     * @param trashId Trash 标识
     */
    public record TrashPayload(String trashId) {
        /** 校验标识。 */
        public TrashPayload {
            trashId = text(trashId, "trashId");
        }
    }

    /**
     * Trash 永久清除危险确认。
     *
     * @param trashId Trash 标识
     * @param confirmation 必须精确等于 {@code PURGE <trashId>}
     */
    public record TrashPurgePayload(String trashId, String confirmation) {
        /** 校验危险确认。 */
        public TrashPurgePayload {
            trashId = text(trashId, "trashId");
            confirmation = Objects.requireNonNull(confirmation, "confirmation");
            if (!confirmation.equals("PURGE " + trashId)) {
                throw new IllegalArgumentException("trash purge confirmation does not match");
            }
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String keyId(String value) {
        String normalized = text(value, "keyId");
        if (!normalized.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("invalid trust key id");
        }
        return normalized;
    }

    private static String digest(String value, String name) {
        String normalized = text(value, name).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return normalized;
    }
}
