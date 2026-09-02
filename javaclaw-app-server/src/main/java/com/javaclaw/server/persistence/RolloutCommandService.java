package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.RolloutManifest;
import com.javaclaw.api.RolloutSnapshot;
import com.javaclaw.api.ThreadId;
import com.javaclaw.protocol.CanonicalJson;

/** Rollout 文件副作用的幂等执行与崩溃恢复服务。 */
public final class RolloutCommandService {
    private final H2Transactions transactions;
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final RolloutExporter exporter;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建导出命令服务。
     *
     * @param database data-v5 数据库
     * @param json 共享 JSON codec
     * @param clock 平台时钟
     */
    public RolloutCommandService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        exporter = new RolloutExporter(database, json, clock);
    }

    /**
     * 导出或恢复已经提交的 Rollout 文件。
     *
     * <p>进程若在文件原子落盘后、命令结果入库前崩溃，重试会先完整校验现有文件，再补记命令结果；不会覆盖目标文件。
     *
     * @param identity 幂等身份；expected revision 是 Thread 快照版本
     * @param threadId Thread
     * @param outputFile 目标文件
     * @return manifest
     */
    public RolloutManifest export(CommandIdentity identity, ThreadId threadId, Path outputFile) {
        Objects.requireNonNull(identity, "identity");
        if (identity.expectedRevision() < 1) {
            throw PersistenceException.invalidRequest("Rollout 必须提供正数 expected revision");
        }
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            Optional<RolloutManifest> recovered = recover(identity);
            if (recovered.isPresent()) {
                return recovered.orElseThrow();
            }
            RolloutManifest manifest = exportOrVerify(threadId, identity.expectedRevision(), outputFile);
            persist(identity, manifest);
            return manifest;
        }
    }

    private Optional<RolloutManifest> recover(CommandIdentity identity) {
        return execute(connection ->
                idempotency.find(connection, identity.idempotencyKey()).map(stored -> recover(identity, stored)));
    }

    private RolloutManifest recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), RolloutManifest.class);
    }

    private RolloutManifest exportOrVerify(ThreadId threadId, long sourceRevision, Path outputFile) {
        Path target = Objects.requireNonNull(outputFile, "outputFile")
                .toAbsolutePath()
                .normalize();
        RolloutManifest manifest;
        if (Files.exists(target)) {
            RolloutSnapshot snapshot = exporter.verify(target);
            manifest = snapshot.manifest();
        } else {
            manifest = exporter.export(threadId, sourceRevision, target);
        }
        if (!manifest.threadId().equals(threadId) || manifest.sourceRevision() != sourceRevision) {
            throw new PersistenceException("现有 Rollout 与导出请求不一致");
        }
        return manifest;
    }

    private void persist(CommandIdentity identity, RolloutManifest manifest) {
        execute(connection -> {
            Optional<IdempotencyRepository.StoredCommand> stored =
                    idempotency.find(connection, identity.idempotencyKey());
            if (stored.isPresent()) {
                recover(identity, stored.orElseThrow());
                return null;
            }
            idempotency.insert(connection, identity, json.encode(manifest), now());
            return null;
        });
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Rollout 命令事务失败", failure);
        }
    }
}
