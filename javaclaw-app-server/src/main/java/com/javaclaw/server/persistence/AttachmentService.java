package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.AttachmentUploadSession;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 内容寻址附件的有界、幂等创建与校验读取用例。 */
public final class AttachmentService {
    private final H2Transactions transactions;
    private final AttachmentRepository attachments = new AttachmentRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final AttachmentBlobStore blobs;
    private final CanonicalJson json;
    private final Clock clock;
    private final AttachmentUploadService uploads;

    /**
     * 创建附件服务。
     *
     * @param database data-v5 数据库与 Blob 根目录
     * @param json 共享 JSON codec
     * @param clock 平台时钟
     */
    public AttachmentService(H2Database database, CanonicalJson json, Clock clock) {
        H2Database checkedDatabase = Objects.requireNonNull(database, "database");
        transactions = new H2Transactions(checkedDatabase);
        blobs = new AttachmentBlobStore(checkedDatabase.dataRoot());
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        uploads = new AttachmentUploadService(checkedDatabase, this, this.json, this.clock);
    }

    /**
     * 原子写入内容寻址附件。
     *
     * <p>实现说明：Blob 先以临时文件原子落盘，再提交元数据与命令结果；崩溃重试会校验同一摘要文件后补记事务。
     *
     * @param scope Attachment 的显式所有权范围
     * @param identity 幂等命令身份；expected revision 必须为 0
     * @param mediaType MIME 类型
     * @param content App Server 内部产生的字节；硬上限仍为 64 MiB，此方法不暴露为 RPC
     * @return 附件元数据
     */
    public AttachmentMetadata store(AttachmentScope scope, CommandIdentity identity, String mediaType, byte[] content) {
        AttachmentScope checkedScope = Objects.requireNonNull(scope, "scope");
        CommandIdentity checkedIdentity = Objects.requireNonNull(identity, "identity");
        if (checkedIdentity.expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("附件创建 expected revision 必须为 0");
        }
        String checkedMediaType = Objects.requireNonNull(mediaType, "mediaType").strip();
        byte[] ownedContent = Objects.requireNonNull(content, "content").clone();
        if (ownedContent.length > AttachmentRpcContracts.MAX_ATTACHMENT_BYTES) {
            throw PersistenceException.invalidRequest("内部 Attachment 超过 64 MiB 硬上限");
        }
        String digest = HexFormat.of().formatHex(sha256(ownedContent));
        synchronized (CommandLocks.forKey(checkedIdentity.idempotencyKey())) {
            Optional<AttachmentMetadata> recovered =
                    recover(checkedIdentity, checkedScope, checkedMediaType, digest, ownedContent.length);
            if (recovered.isPresent()) {
                return recovered.orElseThrow();
            }
            synchronized (CommandLocks.forKey("attachment-digest:" + digest)) {
                String path = blobs.write(digest, ownedContent);
                return persist(checkedScope, checkedIdentity, checkedMediaType, digest, ownedContent.length, path);
            }
        }
    }

    /**
     * 创建分块上传会话并预留 staging 配额。
     *
     * @param scope 上传完成后写入的显式所有权范围
     * @param identity begin 命令身份
     * @param mediaType 最终 MIME 类型
     * @param expectedDigest 预期 SHA-256
     * @param expectedSizeBytes 预期总字节数
     * @return revision 1 的 ACTIVE 会话
     */
    public AttachmentUploadSession beginUpload(
            AttachmentScope scope,
            CommandIdentity identity,
            String mediaType,
            String expectedDigest,
            long expectedSizeBytes) {
        AttachmentRpcContracts.BeginPayload payload =
                new AttachmentRpcContracts.BeginPayload(scope, mediaType, expectedDigest, expectedSizeBytes);
        return uploads.begin(
                identity, payload.scope(), payload.mediaType(), payload.expectedDigest(), payload.expectedSizeBytes());
    }

    /**
     * 追加会话声明的下一个有界 chunk。
     *
     * @param scope 必须与上传会话一致的所有权范围
     * @param identity chunk 命令身份
     * @param uploadId 上传 UUID
     * @param chunkIndex 连续序号
     * @param content 最大 256 KiB 的原始字节
     * @return 推进后的会话
     */
    public AttachmentUploadSession appendUploadChunk(
            AttachmentScope scope, CommandIdentity identity, String uploadId, int chunkIndex, byte[] content) {
        AttachmentRpcContracts.ChunkPayload payload =
                new AttachmentRpcContracts.ChunkPayload(scope, uploadId, chunkIndex, content);
        return uploads.append(identity, payload.scope(), payload.uploadId(), payload.chunkIndex(), payload.content());
    }

    /**
     * 校验总大小与摘要并提交 Attachment。
     *
     * @param scope 必须与上传会话一致的所有权范围
     * @param identity complete 命令身份
     * @param uploadId 上传 UUID
     * @return 内容寻址元数据
     */
    public AttachmentMetadata completeUpload(AttachmentScope scope, CommandIdentity identity, String uploadId) {
        AttachmentRpcContracts.CompletePayload payload = new AttachmentRpcContracts.CompletePayload(scope, uploadId);
        return uploads.complete(identity, payload.scope(), payload.uploadId());
    }

    /**
     * 中止会话并回收 staging。
     *
     * @param scope 必须与上传会话一致的所有权范围
     * @param identity abort 命令身份
     * @param uploadId 上传 UUID
     * @param reason 脱敏原因
     * @return ABORTED 会话
     */
    public AttachmentUploadSession abortUpload(
            AttachmentScope scope, CommandIdentity identity, String uploadId, String reason) {
        AttachmentRpcContracts.AbortPayload payload = new AttachmentRpcContracts.AbortPayload(scope, uploadId, reason);
        return uploads.abort(identity, payload.scope(), payload.uploadId(), payload.reason());
    }

    /**
     * 读取上传会话的权威 revision 和状态。
     *
     * @param scope 必须与上传会话一致的所有权范围
     * @param uploadId 上传 UUID
     * @return 会话快照
     */
    public AttachmentUploadSession readUpload(AttachmentScope scope, String uploadId) {
        AttachmentRpcContracts.UploadReadPayload payload =
                new AttachmentRpcContracts.UploadReadPayload(scope, uploadId);
        return uploads.read(payload.scope(), payload.uploadId());
    }

    /**
     * 按摘要读取并重新校验附件。
     *
     * @param scope 要核验的显式所有权范围
     * @param digest SHA-256 摘要
     * @return 元数据与原始内容
     */
    public AttachmentContent read(AttachmentScope scope, String digest) {
        AttachmentScope checkedScope = Objects.requireNonNull(scope, "scope");
        String checkedDigest = Objects.requireNonNull(digest, "digest");
        AttachmentRepository.StoredAttachment attachment = execute(connection -> attachments
                .findOwned(connection, checkedScope, checkedDigest)
                .orElseThrow(() -> PersistenceException.invalidRequest("Attachment 不属于请求的所有权范围")));
        return new AttachmentContent(attachment.metadata(), blobs.read(attachment));
    }

    /**
     * 核验引用属于指定 Workspace，且客户端声明与权威元数据完全一致。
     *
     * @param workspaceId 所有者 Workspace
     * @param reference 待核验引用
     * @return 权威 Attachment 元数据
     */
    public AttachmentMetadata requireOwned(WorkspaceId workspaceId, AttachmentRef reference) {
        AttachmentRef checked = Objects.requireNonNull(reference, "reference");
        AttachmentMetadata metadata = readMetadata(
                AttachmentScope.workspace(Objects.requireNonNull(workspaceId, "workspaceId")), checked.digest());
        if (!metadata.mediaType().equals(checked.mediaType()) || metadata.sizeBytes() != checked.sizeBytes()) {
            throw PersistenceException.invalidRequest("Attachment 引用与 Workspace 所有的权威元数据不一致");
        }
        return metadata;
    }

    String writeVerifiedBlob(String digest, long size, Path source) {
        if (size < 1 || size > AttachmentRpcContracts.MAX_ATTACHMENT_BYTES) {
            throw PersistenceException.invalidRequest("分块 Attachment 总大小越界");
        }
        if (!Objects.requireNonNull(digest, "digest").matches("[0-9a-f]{64}")) {
            throw PersistenceException.invalidRequest("分块 Attachment 摘要无效");
        }
        return blobs.writeVerified(digest, Objects.requireNonNull(source, "source"), size);
    }

    private AttachmentMetadata persist(
            AttachmentScope scope, CommandIdentity identity, String mediaType, String digest, int size, String path) {
        return execute(connection -> {
            Optional<IdempotencyRepository.StoredCommand> command =
                    idempotency.find(connection, identity.idempotencyKey());
            if (command.isPresent()) {
                return validateRecovered(connection, identity, command.orElseThrow(), scope, mediaType, digest, size);
            }
            AttachmentMetadata metadata = persistVerified(connection, scope, mediaType, digest, size, path);
            idempotency.insert(connection, identity, json.encode(metadata), clock.instant());
            return metadata;
        });
    }

    AttachmentMetadata persistVerified(
            Connection connection, AttachmentScope scope, String mediaType, String digest, long size, String path)
            throws SQLException {
        AttachmentScope checkedScope = Objects.requireNonNull(scope, "scope");
        String checkedMediaType = Objects.requireNonNull(mediaType, "mediaType").strip();
        Optional<AttachmentRepository.StoredBlob> current = attachments.findBlob(connection, digest);
        AttachmentRepository.StoredBlob blob = current.isPresent()
                ? requireSameBlob(current.orElseThrow(), size)
                : new AttachmentRepository.StoredBlob(digest, size, path, clock.instant());
        if (current.isEmpty()) {
            attachments.insertBlob(connection, blob);
        }
        attachments.claim(connection, checkedScope, digest, checkedMediaType, clock.instant());
        return new AttachmentMetadata(digest, checkedMediaType, size, blob.createdAt());
    }

    private AttachmentMetadata readMetadata(AttachmentScope scope, String digest) {
        return execute(connection -> attachments
                .findOwned(connection, scope, digest)
                .orElseThrow(() -> PersistenceException.invalidRequest("Attachment 不属于请求的所有权范围"))
                .metadata());
    }

    private Optional<AttachmentMetadata> recover(
            CommandIdentity identity, AttachmentScope scope, String mediaType, String digest, long size) {
        return execute(connection -> idempotency
                .find(connection, identity.idempotencyKey())
                .map(stored -> validateRecovered(connection, identity, stored, scope, mediaType, digest, size)));
    }

    private AttachmentMetadata recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), AttachmentMetadata.class);
    }

    private AttachmentMetadata validateRecovered(
            Connection connection,
            CommandIdentity identity,
            IdempotencyRepository.StoredCommand stored,
            AttachmentScope scope,
            String mediaType,
            String digest,
            long size) {
        AttachmentMetadata recovered = recover(identity, stored);
        AttachmentMetadata owned;
        try {
            owned = attachments
                    .findOwned(connection, scope, digest)
                    .orElseThrow(() -> PersistenceException.idempotencyConflict("幂等 Attachment 结果不属于当前所有权范围"))
                    .metadata();
        } catch (SQLException failure) {
            throw new PersistenceException("附件幂等恢复核验失败", failure);
        }
        if (!recovered.equals(owned)
                || !owned.mediaType().equals(mediaType)
                || owned.sizeBytes() != size
                || !owned.digest().equals(digest)) {
            throw PersistenceException.idempotencyConflict("幂等 Attachment 输入与已提交结果不一致");
        }
        return owned;
    }

    private static AttachmentRepository.StoredBlob requireSameBlob(AttachmentRepository.StoredBlob current, long size) {
        if (current.sizeBytes() != size) {
            throw new PersistenceException("相同内容摘要已绑定不同 Blob 大小");
        }
        return current;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("附件事务失败", failure);
        }
    }
}
