package com.javaclaw.server.persistence;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.RolloutManifest;
import com.javaclaw.api.RolloutSnapshot;
import com.javaclaw.api.ThreadId;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 从一致性 H2 快照生成并校验 Rollout JSONL。
 *
 * <p>该类型只提供 export 与只读 verify/replay，不存在导入或写回数据库的入口。
 */
public final class RolloutExporter {
    private static final String ZERO_HASH = "0".repeat(64);

    private final H2Database database;
    private final CanonicalJson json;
    private final Clock clock;
    private final ItemRepository items = new ItemRepository(new TurnRepository());
    private final ThreadRepository threads = new ThreadRepository();

    /**
     * 创建 Rollout 服务。
     *
     * @param database data-v5 数据库
     * @param json 共享规范 JSON codec
     * @param clock 平台时钟
     */
    public RolloutExporter(H2Database database, CanonicalJson json, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 导出 Thread；目标文件已存在时失败，避免覆盖用户数据。
     *
     * @param threadId Thread
     * @param sourceRevision 必须匹配的一致性 Thread revision
     * @param outputFile 目标 JSONL 文件
     * @return 写入的 manifest
     */
    public RolloutManifest export(ThreadId threadId, long sourceRevision, Path outputFile) {
        Objects.requireNonNull(threadId, "threadId");
        if (sourceRevision < 1) {
            throw new IllegalArgumentException("sourceRevision must be positive");
        }
        Path target = Objects.requireNonNull(outputFile, "outputFile")
                .toAbsolutePath()
                .normalize();
        Path parent = Objects.requireNonNull(target.getParent(), "outputFile parent");
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".javaclaw-rollout-", ".jsonl.tmp");
            RolloutManifest manifest = writeSnapshot(threadId, sourceRevision, temporary);
            moveWithoutOverwrite(temporary, target);
            temporary = null;
            return manifest;
        } catch (IOException failure) {
            throw new PersistenceException("Rollout 导出失败", failure);
        } finally {
            deleteTemporary(temporary);
        }
    }

    /**
     * 校验 Rollout 并返回不可变离线回放视图。
     *
     * @param inputFile Rollout JSONL
     * @return 已验证快照
     */
    public RolloutSnapshot verify(Path inputFile) {
        Path source =
                Objects.requireNonNull(inputFile, "inputFile").toAbsolutePath().normalize();
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            return readAndVerify(reader);
        } catch (IOException failure) {
            throw new PersistenceException("Rollout 读取失败", failure);
        }
    }

    private RolloutManifest writeSnapshot(ThreadId threadId, long sourceRevision, Path temporary) throws IOException {
        List<ItemEnvelope> snapshot = readSnapshot(threadId, sourceRevision);
        MessageDigest total = sha256();
        String previousHash = ZERO_HASH;
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            for (ItemEnvelope item : snapshot) {
                ChainContent content = new ChainContent("item", item.sequence(), previousHash, item);
                String hash = digest(json.encode(content).json());
                String line = json.encode(new ItemLine("item", item.sequence(), previousHash, hash, item))
                        .json();
                writeLine(writer, total, line);
                previousHash = hash;
            }
            RolloutManifest manifest = new RolloutManifest(
                    threadId,
                    sourceRevision,
                    snapshot.size(),
                    snapshot.isEmpty() ? 0 : snapshot.getLast().sequence(),
                    previousHash,
                    HexFormat.of().formatHex(total.digest()),
                    Instant.now(clock));
            writer.write(json.encode(new ManifestLine("manifest", manifest)).json());
            writer.newLine();
            return manifest;
        }
    }

    private List<ItemEnvelope> readSnapshot(ThreadId threadId, long sourceRevision) {
        try (Connection connection = database.open()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            com.javaclaw.api.ConversationThread thread = threads.find(connection, threadId)
                    .orElseThrow(() -> new PersistenceException("Rollout Thread 不存在"));
            if (thread.revision() != sourceRevision) {
                throw PersistenceException.revisionConflict("Rollout Thread revision 已改变");
            }
            List<ItemEnvelope> snapshot = items.listByThread(connection, threadId);
            connection.commit();
            return snapshot;
        } catch (SQLException failure) {
            throw new PersistenceException("Rollout 一致性快照读取失败", failure);
        }
    }

    private RolloutSnapshot readAndVerify(BufferedReader reader) throws IOException {
        VerificationState state = new VerificationState(sha256());
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                throw new PersistenceException("Rollout 不允许空行");
            }
            String recordType = json.textField(json.parse(line), "recordType")
                    .orElseThrow(() -> new PersistenceException("Rollout 缺少 recordType"));
            if ("item".equals(recordType)) {
                state.acceptItem(line, json);
            } else if ("manifest".equals(recordType)) {
                state.acceptManifest(line, json);
            } else {
                throw new PersistenceException("Rollout recordType 无效");
            }
        }
        return state.finish();
    }

    private static void writeLine(BufferedWriter writer, MessageDigest total, String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        writer.write(line);
        writer.newLine();
        total.update(bytes);
    }

    private static void moveWithoutOverwrite(Path source, Path target) throws IOException {
        // 临时文件与目标位于同一目录。普通 move 保留“目标存在即失败”语义；部分平台的
        // ATOMIC_MOVE 会忽略未设置 REPLACE_EXISTING 这一事实并覆盖目标，因此不能用于这里。
        Files.move(source, target);
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // 临时文件清理失败不覆盖原始导出异常；父目录中的前缀可用于人工识别。
        }
    }

    private static String digest(String value) {
        MessageDigest digest = sha256();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record ChainContent(String recordType, long sequence, String previousHash, ItemEnvelope item) {}

    private record ItemLine(String recordType, long sequence, String previousHash, String hash, ItemEnvelope item) {}

    private record ManifestLine(String recordType, RolloutManifest manifest) {}

    private static final class VerificationState {
        private final MessageDigest total;
        private final List<ItemEnvelope> items = new ArrayList<>();
        private String previousHash = ZERO_HASH;
        private long previousSequence;
        private RolloutManifest manifest;

        private VerificationState(MessageDigest total) {
            this.total = total;
        }

        private void acceptItem(String rawLine, CanonicalJson json) {
            if (manifest != null) {
                throw new PersistenceException("Rollout manifest 必须是最后一行");
            }
            ItemLine line = json.decode(json.parse(rawLine), ItemLine.class);
            requireSequence(line);
            ChainContent content = new ChainContent("item", line.sequence(), line.previousHash(), line.item());
            String expectedHash = digest(json.encode(content).json());
            if (!previousHash.equals(line.previousHash()) || !expectedHash.equals(line.hash())) {
                throw new PersistenceException("Rollout 哈希链校验失败");
            }
            total.update((rawLine + "\n").getBytes(StandardCharsets.UTF_8));
            items.add(line.item());
            previousHash = line.hash();
            previousSequence = line.sequence();
        }

        private void acceptManifest(String rawLine, CanonicalJson json) {
            if (manifest != null) {
                throw new PersistenceException("Rollout 只能包含一个 manifest");
            }
            manifest = json.decode(json.parse(rawLine), ManifestLine.class).manifest();
            if (manifest == null) {
                throw new PersistenceException("Rollout manifest 缺失");
            }
        }

        private RolloutSnapshot finish() {
            if (manifest == null) {
                throw new PersistenceException("Rollout 缺少 manifest");
            }
            String totalHash = HexFormat.of().formatHex(total.digest());
            boolean valid = manifest.itemCount() == items.size()
                    && manifest.lastSequence() == previousSequence
                    && manifest.finalChainHash().equals(previousHash)
                    && manifest.totalSha256().equals(totalHash);
            if (!valid) {
                throw new PersistenceException("Rollout manifest 完整性校验失败");
            }
            return new RolloutSnapshot(manifest, items);
        }

        private void requireSequence(ItemLine line) {
            long expected = previousSequence + 1;
            if (line.sequence() != expected
                    || line.item() == null
                    || line.item().sequence() != expected) {
                throw new PersistenceException("Rollout sequence 不连续");
            }
        }
    }
}
