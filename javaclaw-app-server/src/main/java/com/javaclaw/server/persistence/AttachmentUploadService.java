package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.AttachmentUploadSession;
import com.javaclaw.api.AttachmentUploadState;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 分块 Attachment 上传状态机。
 *
 * <p>实现说明：每个上传由资源锁串行推进，数据库 revision 是跨重启权威状态。chunk 先原子落入受管 staging，再与幂等结果同事务推进；崩溃重试会校验已落盘 chunk，不会重复追加。 complete 的
 * Workspace claim、会话终态和幂等结果必须在同一事务提交；Blob 可先原子落盘，但未提交 claim 的孤儿 Blob 绝不构成所有权证据。
 */
final class AttachmentUploadService {
    static final int MAX_ACTIVE_UPLOADS = 32;
    static final long MAX_RESERVED_BYTES = 256L * 1024 * 1024;
    static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private static final String EXPIRED_REASON = "上传会话已超过 30 分钟有效期";
    private static final Object STAGING_LIFECYCLE_LOCK = new Object();

    private final H2Transactions transactions;
    private final AttachmentUploadRepository uploads = new AttachmentUploadRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final AttachmentUploadStore staging;
    private final AttachmentService attachments;
    private final CanonicalJson json;
    private final Clock clock;

    AttachmentUploadService(H2Database database, AttachmentService attachments, CanonicalJson json, Clock clock) {
        H2Database checkedDatabase = Objects.requireNonNull(database, "database");
        transactions = new H2Transactions(checkedDatabase);
        staging = new AttachmentUploadStore(checkedDatabase.dataRoot());
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        reapExpiredAndOrphaned();
    }

    AttachmentUploadSession begin(
            CommandIdentity identity,
            AttachmentScope scope,
            String mediaType,
            String expectedDigest,
            long expectedSizeBytes) {
        CommandIdentity checked = requireCreate(identity);
        AttachmentScope checkedScope = Objects.requireNonNull(scope, "scope");
        reapExpiredAndOrphaned();
        synchronized (CommandLocks.forKey(checked.idempotencyKey())) {
            Optional<AttachmentUploadSession> recovered = recover(checked, AttachmentUploadSession.class);
            if (recovered.isPresent()) {
                return validateBeginRecovery(
                        recovered.orElseThrow(), checkedScope, mediaType, expectedDigest, expectedSizeBytes);
            }
            return reserve(checked, checkedScope, mediaType, expectedDigest, expectedSizeBytes);
        }
    }

    AttachmentUploadSession append(
            CommandIdentity identity, AttachmentScope scope, String uploadId, int chunkIndex, byte[] content) {
        CommandIdentity checked = requireMutation(identity);
        AttachmentScope checkedScope = Objects.requireNonNull(scope, "scope");
        byte[] ownedContent = Objects.requireNonNull(content, "content").clone();
        reapExpiredAndOrphaned();
        synchronized (CommandLocks.forKey(checked.idempotencyKey())) {
            synchronized (resourceLock(uploadId)) {
                Optional<AttachmentUploadSession> recovered = recover(checked, AttachmentUploadSession.class);
                if (recovered.isPresent()) {
                    return validateAppendRecovery(recovered.orElseThrow(), checkedScope, uploadId, checked, chunkIndex);
                }
                AttachmentUploadSession current = requireActive(uploadId, checked.expectedRevision(), checkedScope);
                requireNextChunk(current, chunkIndex, ownedContent.length);
                stageChunkOrFail(current, chunkIndex, ownedContent);
                return advance(checked, current, ownedContent.length);
            }
        }
    }

    AttachmentMetadata complete(CommandIdentity identity, AttachmentScope scope, String uploadId) {
        CommandIdentity checked = requireMutation(identity);
        AttachmentScope checkedScope = Objects.requireNonNull(scope, "scope");
        reapExpiredAndOrphaned();
        synchronized (CommandLocks.forKey(checked.idempotencyKey())) {
            synchronized (resourceLock(uploadId)) {
                Optional<AttachmentMetadata> recovered = recover(checked, AttachmentMetadata.class);
                if (recovered.isPresent()) {
                    validateCompleteRecovery(
                            recovered.orElseThrow(), checkedScope, uploadId, checked.expectedRevision());
                    cleanupQuietly(uploadId);
                    return recovered.orElseThrow();
                }
                AttachmentUploadSession current = requireActive(uploadId, checked.expectedRevision(), checkedScope);
                if (current.receivedSizeBytes() != current.expectedSizeBytes()) {
                    throw PersistenceException.invalidRequest("Attachment 上传尚未收到声明的全部字节");
                }
                AttachmentUploadStore.AssembledAttachment assembled = assembleOrFail(current);
                AttachmentUploadSession completed;
                synchronized (CommandLocks.forKey("attachment-digest:" + assembled.digest())) {
                    String path =
                            attachments.writeVerifiedBlob(assembled.digest(), assembled.sizeBytes(), assembled.path());
                    completed = finish(checked, current, assembled, path);
                }
                cleanupQuietly(uploadId);
                return completed.attachment().orElseThrow();
            }
        }
    }

    AttachmentUploadSession abort(CommandIdentity identity, AttachmentScope scope, String uploadId, String reason) {
        CommandIdentity checked = requireMutation(identity);
        AttachmentScope checkedScope = Objects.requireNonNull(scope, "scope");
        synchronized (CommandLocks.forKey(checked.idempotencyKey())) {
            synchronized (resourceLock(uploadId)) {
                Optional<AttachmentUploadSession> recovered = recover(checked, AttachmentUploadSession.class);
                if (recovered.isPresent()) {
                    validateTerminalRecovery(
                            recovered.orElseThrow(), checkedScope, uploadId, checked, AttachmentUploadState.ABORTED);
                    cleanupQuietly(uploadId);
                    return recovered.orElseThrow();
                }
                AttachmentUploadSession current = requireActive(uploadId, checked.expectedRevision(), checkedScope);
                AttachmentUploadSession aborted =
                        terminate(checked, current, AttachmentUploadState.ABORTED, sanitizedReason(reason));
                cleanupQuietly(uploadId);
                return aborted;
            }
        }
    }

    AttachmentUploadSession read(AttachmentScope scope, String uploadId) {
        reapExpiredAndOrphaned();
        AttachmentUploadSession current =
                execute(connection -> uploads.find(connection, Objects.requireNonNull(uploadId, "uploadId"))
                        .orElseThrow(() -> PersistenceException.invalidRequest("Attachment 上传会话不存在")));
        return requireScope(current, Objects.requireNonNull(scope, "scope"));
    }

    private AttachmentUploadSession reserve(
            CommandIdentity identity,
            AttachmentScope scope,
            String mediaType,
            String expectedDigest,
            long expectedSizeBytes) {
        String uploadId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        AttachmentUploadSession session = new AttachmentUploadSession(
                uploadId,
                scope,
                AttachmentUploadState.ACTIVE,
                mediaType,
                expectedDigest,
                expectedSizeBytes,
                0,
                0,
                1,
                now,
                now,
                now.plus(SESSION_TTL),
                Optional.empty(),
                Optional.empty());
        synchronized (STAGING_LIFECYCLE_LOCK) {
            staging.create(uploadId);
            try {
                return execute(connection -> {
                    uploads.lockQuota(connection);
                    requireAvailableQuota(uploads.activeUsage(connection), expectedSizeBytes);
                    uploads.insert(connection, session);
                    idempotency.insert(connection, identity, json.encode(session), now);
                    return session;
                });
            } catch (RuntimeException failure) {
                cleanupQuietly(uploadId);
                throw failure;
            }
        }
    }

    private AttachmentUploadSession advance(CommandIdentity identity, AttachmentUploadSession current, int chunkBytes) {
        Instant now = clock.instant();
        return execute(connection -> {
            AttachmentUploadSession latest = requireCurrent(connection, current.id(), identity.expectedRevision());
            requireNextChunk(latest, current.nextChunkIndex(), chunkBytes);
            AttachmentUploadSession advanced =
                    uploads.advance(connection, latest.id(), latest.revision(), chunkBytes, now, now.plus(SESSION_TTL));
            idempotency.insert(connection, identity, json.encode(advanced), now);
            return advanced;
        });
    }

    private AttachmentUploadSession finish(
            CommandIdentity identity,
            AttachmentUploadSession current,
            AttachmentUploadStore.AssembledAttachment assembled,
            String path) {
        Instant now = clock.instant();
        return execute(connection -> {
            AttachmentUploadSession latest = requireCurrent(connection, current.id(), identity.expectedRevision());
            AttachmentMetadata metadata = attachments.persistVerified(
                    connection, latest.scope(), latest.mediaType(), assembled.digest(), assembled.sizeBytes(), path);
            AttachmentUploadSession completed =
                    uploads.complete(connection, latest.id(), latest.revision(), metadata, now);
            idempotency.insert(connection, identity, json.encode(metadata), now);
            return completed;
        });
    }

    private AttachmentUploadSession terminate(
            CommandIdentity identity, AttachmentUploadSession current, AttachmentUploadState state, String reason) {
        Instant now = clock.instant();
        return execute(connection -> {
            AttachmentUploadSession latest = requireCurrent(connection, current.id(), identity.expectedRevision());
            AttachmentUploadSession terminated =
                    uploads.terminate(connection, latest.id(), latest.revision(), state, reason, now);
            idempotency.insert(connection, identity, json.encode(terminated), now);
            return terminated;
        });
    }

    private AttachmentUploadSession requireCurrent(
            java.sql.Connection connection, String uploadId, long expectedRevision) throws java.sql.SQLException {
        AttachmentUploadSession current = uploads.find(connection, uploadId)
                .orElseThrow(() -> PersistenceException.invalidRequest("Attachment 上传会话不存在"));
        return requireActive(current, expectedRevision);
    }

    private AttachmentUploadSession requireActive(String uploadId, long expectedRevision, AttachmentScope scope) {
        AttachmentUploadSession current = execute(connection -> uploads.find(connection, uploadId)
                .orElseThrow(() -> PersistenceException.invalidRequest("Attachment 上传会话不存在")));
        return requireActive(requireScope(current, scope), expectedRevision);
    }

    private static AttachmentUploadSession requireActive(AttachmentUploadSession current, long expectedRevision) {
        if (current.state() != AttachmentUploadState.ACTIVE) {
            throw PersistenceException.invalidRequest("Attachment 上传会话已结束");
        }
        if (current.revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Attachment 上传 revision 已变化");
        }
        return current;
    }

    private static AttachmentUploadSession requireScope(AttachmentUploadSession current, AttachmentScope expected) {
        if (!current.scope().equals(expected)) {
            throw PersistenceException.invalidRequest("Attachment 上传会话不属于请求的所有权范围");
        }
        return current;
    }

    private static AttachmentUploadSession validateBeginRecovery(
            AttachmentUploadSession recovered,
            AttachmentScope scope,
            String mediaType,
            String expectedDigest,
            long expectedSizeBytes) {
        AttachmentUploadSession checked = requireRecoveredScope(recovered, scope);
        if (!checked.mediaType().equals(mediaType.strip())
                || !checked.expectedDigest().equals(expectedDigest.toLowerCase(java.util.Locale.ROOT))
                || checked.expectedSizeBytes() != expectedSizeBytes) {
            throw PersistenceException.idempotencyConflict("Attachment begin 输入与幂等结果不一致");
        }
        return checked;
    }

    private static AttachmentUploadSession validateAppendRecovery(
            AttachmentUploadSession recovered,
            AttachmentScope scope,
            String uploadId,
            CommandIdentity identity,
            int chunkIndex) {
        AttachmentUploadSession checked = requireSession(recovered, scope, uploadId);
        if (checked.revision() != Math.incrementExact(identity.expectedRevision())
                || checked.nextChunkIndex() != Math.incrementExact(chunkIndex)) {
            throw PersistenceException.idempotencyConflict("Attachment chunk 输入与幂等结果不一致");
        }
        return checked;
    }

    private void validateCompleteRecovery(
            AttachmentMetadata recovered, AttachmentScope scope, String uploadId, long expectedRevision) {
        AttachmentUploadSession persisted = execute(connection -> uploads.find(connection, uploadId)
                .orElseThrow(() -> PersistenceException.idempotencyConflict("Attachment 幂等结果对应的上传会话不存在")));
        AttachmentUploadSession completed = requireSession(persisted, scope, uploadId);
        if (completed.state() != AttachmentUploadState.COMPLETED
                || completed.revision() != Math.incrementExact(expectedRevision)
                || !completed.attachment().filter(recovered::equals).isPresent()) {
            throw PersistenceException.idempotencyConflict("Attachment complete 输入与幂等结果不一致");
        }
    }

    private static AttachmentUploadSession validateTerminalRecovery(
            AttachmentUploadSession recovered,
            AttachmentScope scope,
            String uploadId,
            CommandIdentity identity,
            AttachmentUploadState state) {
        AttachmentUploadSession checked = requireSession(recovered, scope, uploadId);
        if (checked.state() != state || checked.revision() != Math.incrementExact(identity.expectedRevision())) {
            throw PersistenceException.idempotencyConflict("Attachment 终止输入与幂等结果不一致");
        }
        return checked;
    }

    private static AttachmentUploadSession requireSession(
            AttachmentUploadSession session, AttachmentScope scope, String uploadId) {
        AttachmentUploadSession checked = requireRecoveredScope(session, scope);
        if (!checked.id().equals(uploadId)) {
            throw PersistenceException.idempotencyConflict("Attachment 幂等结果属于其他上传会话");
        }
        return checked;
    }

    private static AttachmentUploadSession requireRecoveredScope(
            AttachmentUploadSession session, AttachmentScope expected) {
        if (!session.scope().equals(expected)) {
            throw PersistenceException.idempotencyConflict("Attachment 幂等结果属于其他所有权范围");
        }
        return session;
    }

    private static void requireNextChunk(AttachmentUploadSession current, int chunkIndex, int chunkBytes) {
        if (chunkIndex != current.nextChunkIndex()) {
            throw PersistenceException.revisionConflict("Attachment chunk 序号不是当前 nextChunkIndex");
        }
        if (chunkIndex >= AttachmentRpcContracts.MAX_ATTACHMENT_CHUNKS) {
            throw PersistenceException.invalidRequest("Attachment chunk 数量超过平台上限");
        }
        if (chunkBytes < 1 || chunkBytes > AttachmentRpcContracts.MAX_ATTACHMENT_CHUNK_BYTES) {
            throw PersistenceException.invalidRequest("Attachment chunk 大小超过平台上限");
        }
        if (current.receivedSizeBytes() + chunkBytes > current.expectedSizeBytes()) {
            throw PersistenceException.invalidRequest("Attachment chunk 超过声明的总大小");
        }
    }

    private void stageChunkOrFail(AttachmentUploadSession current, int chunkIndex, byte[] content) {
        try {
            staging.writeChunk(current.id(), chunkIndex, content);
        } catch (RuntimeException failure) {
            failAndCleanup(current, "Attachment staging 写入或校验失败");
            throw failure;
        }
    }

    private AttachmentUploadStore.AssembledAttachment assembleOrFail(AttachmentUploadSession current) {
        try {
            return staging.assemble(current);
        } catch (RuntimeException failure) {
            failAndCleanup(current, "Attachment staging 与声明不一致");
            throw PersistenceException.invalidRequest("Attachment staging 与声明的摘要或大小不一致");
        }
    }

    private void failAndCleanup(AttachmentUploadSession current, String reason) {
        try {
            execute(connection -> uploads.terminate(
                    connection,
                    current.id(),
                    current.revision(),
                    AttachmentUploadState.FAILED,
                    reason,
                    clock.instant()));
        } finally {
            cleanupQuietly(current.id());
        }
    }

    private void reapExpiredAndOrphaned() {
        synchronized (STAGING_LIFECYCLE_LOCK) {
            Instant now = clock.instant();
            List<String> expired = execute(connection -> {
                uploads.lockQuota(connection);
                return uploads.expiredIds(connection, now);
            });
            expired.forEach(uploadId -> expireAndCleanup(uploadId, now));
            Set<String> active = execute(uploads::activeIds);
            staging.cleanupOrphans(active);
        }
    }

    private void expireAndCleanup(String uploadId, Instant now) {
        synchronized (resourceLock(uploadId)) {
            boolean expired = execute(connection -> uploads.expireOne(connection, uploadId, now, EXPIRED_REASON));
            if (expired) {
                cleanupQuietly(uploadId);
            }
        }
    }

    private <T> Optional<T> recover(CommandIdentity identity, Class<T> resultType) {
        return execute(connection -> idempotency
                .find(connection, identity.idempotencyKey())
                .map(stored -> {
                    if (!stored.method().equals(identity.method())
                            || !stored.requestDigest().equals(identity.requestDigest())) {
                        throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
                    }
                    return json.decode(stored.response(), resultType);
                }));
    }

    private static CommandIdentity requireCreate(CommandIdentity identity) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (checked.expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("Attachment upload/begin expected revision 必须为 0");
        }
        return checked;
    }

    private static CommandIdentity requireMutation(CommandIdentity identity) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (checked.expectedRevision() < 1) {
            throw PersistenceException.invalidRequest("Attachment 上传变更 expected revision 必须大于 0");
        }
        return checked;
    }

    private static void requireAvailableQuota(AttachmentUploadRepository.ActiveUsage usage, long requestedBytes) {
        if (usage.count() >= MAX_ACTIVE_UPLOADS || usage.reservedBytes() + requestedBytes > MAX_RESERVED_BYTES) {
            throw PersistenceException.invalidRequest("Attachment 上传 staging 配额已用尽");
        }
    }

    private static String sanitizedReason(String value) {
        String normalized = Objects.requireNonNull(value, "reason").strip();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw PersistenceException.invalidRequest("Attachment 上传取消原因无效");
        }
        return normalized;
    }

    private static Object resourceLock(String uploadId) {
        return CommandLocks.forKey("attachment-upload:" + Objects.requireNonNull(uploadId, "uploadId"));
    }

    private void cleanupQuietly(String uploadId) {
        try {
            staging.cleanup(uploadId);
        } catch (PersistenceException ignored) {
            // 权威状态已经终止；受管 staging 会在下次启动或 begin/read 时再次回收。
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Attachment 上传事务失败", failure);
        }
    }
}
