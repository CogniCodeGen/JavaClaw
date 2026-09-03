package com.javaclaw.server.security.vault;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultManagementAction;
import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.extension.spi.CredentialVaultPort;
import com.javaclaw.nativehost.credential.MasterKeyProtectionException;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CredentialAvailabilityPort;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.IdempotentCommandStore;
import com.javaclaw.server.persistence.PersistenceException;

/**
 * 以系统凭据封装主密钥、以 H2 保存 AES-256-GCM 密文的本地 Secret Vault。
 *
 * <p>Vault 状态和 Secret 操作在同一实例锁内串行，主密钥轮换不会与读取或写入交错。Secret 明文仅在调用栈内短暂存在，服务在 callback 返回后立即清零；callback
 * 不得保留数组引用。系统凭据设施不可用时服务保留管理状态，但所有 Secret 操作均 fail closed。
 *
 * <p><b>并发不变量：</b>监听回调只在 Vault 实例锁外同步执行。这样既保证方法返回前运行时已看到新状态，也避免与 Provider Registry 的刷新锁形成 AB/BA 死锁。安全重建失败不会反向回滚已提交的
 * Vault 变更，但会保持运行时门闩关闭并向调用方报告失败。
 *
 * <p>主密钥先写入系统凭据，再在单个 H2 事务中重加密全部记录并切换 active key。旧 key id 会留在 {@code PREVIOUS_KEY_ID}，直至系统包装确认删除，因此崩溃恢复不会丢失唯一可解密的主密钥。
 */
public final class SecretVaultService implements CredentialAvailabilityPort, CredentialVaultPort, AutoCloseable {
    private static final String KEY_PREFIX = "vault-";

    private final H2Transactions transactions;
    private final VaultRepository repository = new VaultRepository();
    private final IdempotentCommandStore commands = new IdempotentCommandStore();
    private final MasterKeyProtector protector;
    private final CanonicalJson json;
    private final Clock clock;
    private final SecureRandom random;
    private final VaultCipher cipher;
    private final VaultChangeListeners changeListeners;
    private final VaultCredentialTransactions credentialTransactions;
    private final ProviderCredentialVault providerCredentials;

    private byte[] activeKey;
    private String activeKeyId;
    private VaultLockReason lockReason = VaultLockReason.SYSTEM_CREDENTIAL_UNAVAILABLE;
    private boolean closed;

    /**
     * 打开或创建当前安装的 Vault。
     *
     * <p>系统凭据设施失败不会阻止 App Server 启动；状态会变为 {@link VaultState#LOCKED}。
     *
     * @param database 已初始化 V001 baseline 的 data-v5 数据库
     * @param protector 当前操作系统的用户级主密钥保护器
     * @param json 规范 JSON codec，用于持久幂等回执
     * @param clock 平台时钟
     * @param random 加密安全随机源
     */
    public SecretVaultService(
            H2Database database, MasterKeyProtector protector, CanonicalJson json, Clock clock, SecureRandom random) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.protector = Objects.requireNonNull(protector, "protector");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        changeListeners = new VaultChangeListeners(() -> !closed);
        cipher = new VaultCipher(random);
        credentialTransactions = new VaultCredentialTransactions(database, json, clock, random);
        providerCredentials = new ProviderCredentialVault(this, credentialTransactions);
        initialize();
    }

    /**
     * 返回当前脱敏状态，不访问或暴露任何 Secret。
     *
     * @return Vault 状态快照
     */
    public synchronized VaultStatus status() {
        long count = execute(repository::count);
        Optional<VaultRepository.KeyState> keyState = execute(connection -> repository.keyState(connection, false));
        boolean cleanupPending =
                keyState.map(state -> state.previousKeyId() != null).orElse(false);
        return new VaultStatus(
                ready() ? VaultState.READY : VaultState.LOCKED,
                ready() ? VaultLockReason.NONE : lockReason,
                count,
                cleanupPending,
                clock.instant());
    }

    /**
     * 系统凭据设施恢复后重新尝试解封主密钥。
     *
     * @return 刷新后的脱敏状态
     */
    public VaultStatus refresh() {
        return changeListeners.afterAvailabilityRefresh(this, this::ready, () -> {
            requireOpen();
            clearActiveKey();
            initialize();
            return status();
        });
    }

    /**
     * 注册 Secret 业务内容变化监听器，例如热更新 Provider Registry。
     *
     * @param listener 快速、非阻塞监听器
     */
    public void onChange(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * 注册需要在 Vault 变化时先失效、再重建的 Provider 运行时。
     *
     * @param invalidate 立即退役旧凭据运行时的同步操作
     * @param rebuild 从已提交状态重建运行时的同步操作
     */
    public void onRuntimeChange(Runnable invalidate, Runnable rebuild) {
        changeListeners.addRuntime(invalidate, rebuild);
    }

    /** @return 与本 Vault 变化线性化的 Provider 运行时门闩 */
    public VaultRuntimeGate runtimeGate() {
        return changeListeners.runtimeGate();
    }

    /**
     * 创建随机 opaque 引用并保存 Secret。
     *
     * @param identity 创建命令身份；expected revision 必须为 0
     * @param namespace Provider、MCP、OAuth、Site 或 Browser 等资源命名空间
     * @param secret Secret 字节；调用方继续拥有并负责清理原数组
     * @return 仅含引用、版本和时间的元数据
     */
    public CredentialMetadata create(CommandIdentity identity, String namespace, byte[] secret) {
        return changeListeners.afterChange(this, () -> {
            CommandIdentity checkedIdentity = VaultChecks.requireExpectedRevision(identity, 0);
            byte[] key = requireReadyKey();
            try {
                return credentialTransactions.create(checkedIdentity, namespace, secret, key, activeKeyId);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        });
    }

    /**
     * 以乐观锁轮换已有 Secret，引用保持不变。
     *
     * @param identity 轮换命令身份；expected revision 必须匹配当前版本
     * @param reference Vault 引用
     * @param secret 新 Secret；调用方继续拥有并负责清理原数组
     * @return 新版本的脱敏元数据
     */
    public CredentialMetadata rotate(CommandIdentity identity, CredentialRef reference, byte[] secret) {
        return changeListeners.afterChange(this, () -> {
            CommandIdentity checkedIdentity = Objects.requireNonNull(identity, "identity");
            byte[] key = requireReadyKey();
            try {
                return credentialTransactions.rotate(
                        checkedIdentity, Objects.requireNonNull(reference, "reference"), secret, key, activeKeyId);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        });
    }

    /**
     * 查询单个引用的非敏感元数据。
     *
     * @param reference Vault 引用
     * @return 引用存在时的元数据
     */
    public synchronized Optional<CredentialMetadata> metadata(CredentialRef reference) {
        requireOpen();
        return execute(connection -> repository
                .find(connection, Objects.requireNonNull(reference, "reference"), false)
                .map(VaultRepository.StoredSecret::metadata));
    }

    /**
     * 列出一个命名空间中的脱敏元数据；Vault 锁定时仍可用于管理页面展示。
     *
     * @param namespace 精确命名空间
     * @return 按 opaque ID 排序的不可变列表
     */
    @Override
    public synchronized List<CredentialMetadata> listMetadata(String namespace) {
        requireOpen();
        String checked = new CredentialRef(namespace, "metadata-list").namespace();
        return execute(connection -> repository.listMetadata(connection, checked));
    }

    /**
     * 检查引用当前是否可供 Provider 调用使用。
     *
     * @param reference 不含明文的 Secret 引用
     * @return Vault 已解锁且引用存在时为 true
     */
    @Override
    public synchronized boolean available(CredentialRef reference) {
        return ready()
                && metadata(Objects.requireNonNull(reference, "reference")).isPresent();
    }

    /**
     * 清除一个 Secret，使旧 CredentialRef 立即失效。
     *
     * @param identity 清除命令身份；expected revision 必须匹配当前版本
     * @param reference Vault 引用
     * @return 可安全重放的脱敏回执
     */
    public CredentialClearReceipt clear(CommandIdentity identity, CredentialRef reference) {
        return changeListeners.afterChange(this, () -> {
            CommandIdentity checkedIdentity = Objects.requireNonNull(identity, "identity");
            requireReady();
            return credentialTransactions.clear(checkedIdentity, Objects.requireNonNull(reference, "reference"));
        });
    }

    /**
     * 在解封 SealedSecret 前恢复既有创建或轮换结果，避免网络重试被 replay 门禁误拒绝。
     *
     * @param identity 完整命令身份
     * @return 已提交的脱敏结果
     */
    public synchronized Optional<CredentialMetadata> recoverCredential(CommandIdentity identity) {
        requireOpen();
        return credentialTransactions.recover(Objects.requireNonNull(identity, "identity"), CredentialMetadata.class);
    }

    /**
     * 在执行清除前恢复既有回执。
     *
     * @param identity 完整命令身份
     * @return 已提交的清除回执
     */
    public synchronized Optional<CredentialClearReceipt> recoverClear(CommandIdentity identity) {
        requireOpen();
        return credentialTransactions.recover(
                Objects.requireNonNull(identity, "identity"), CredentialClearReceipt.class);
    }

    /** @return Provider 与 Secret 原子复合命令的专用 Vault 边界 */
    public ProviderCredentialVault providerCredentials() {
        return providerCredentials;
    }

    /**
     * 在受控 callback 内使用解密后的 Secret。
     *
     * <p>传入数组仅在 callback 调用期间有效，callback 不得保存引用。无论 callback 是否失败，返回前都会清零数组。
     *
     * @param reference Vault 引用
     * @param operation 使用短生命周期明文字节的操作
     * @param <T> 操作结果类型
     * @return callback 结果
     */
    public synchronized <T> T use(CredentialRef reference, SecretOperation<T> operation) {
        byte[] key = requireReadyKey();
        byte[] plaintext = null;
        try {
            PreparedProviderCredentialMutation prepared = providerCredentials.current(reference);
            if (prepared != null && prepared.matches(reference)) {
                if (!Objects.equals(activeKeyId, prepared.keyId())) {
                    throw new VaultException("Vault 主密钥已变化，请重新提交 Provider Secret");
                }
                return credentialTransactions.usePrepared(
                        prepared, key, Objects.requireNonNull(operation, "operation"));
            }
            VaultRepository.StoredSecret stored = execute(connection -> repository
                    .find(connection, Objects.requireNonNull(reference, "reference"), false)
                    .orElseThrow(() -> PersistenceException.invalidRequest("CredentialRef 不存在")));
            plaintext = cipher.decrypt(key, SecretBinding.from(stored.metadata()), stored.encrypted());
            return Objects.requireNonNull(operation, "operation").use(plaintext);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new VaultException("使用 Vault Secret 的操作失败", failure);
        } finally {
            Arrays.fill(key, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /**
     * 原子轮换当前安装的 256-bit 主密钥。
     *
     * <p>CredentialRef 和各 Secret revision 均保持不变；这是密钥保管操作，不是业务凭据变更。
     *
     * @param identity 幂等命令身份；expected revision 必须为 0
     * @return 被重加密数量与提交时间
     */
    public VaultManagementReceipt rotateMasterKey(CommandIdentity identity) {
        return changeListeners
                .afterConditionalChange(this, () -> rotateMasterKeyLocked(identity), VaultManagementResult::changed)
                .receipt();
    }

    private VaultManagementResult rotateMasterKeyLocked(CommandIdentity identity) {
        CommandIdentity checkedIdentity = VaultChecks.requireExpectedRevision(identity, 0);
        Optional<VaultManagementReceipt> recovered = recover(checkedIdentity, VaultManagementReceipt.class);
        if (recovered.isPresent()) {
            return new VaultManagementResult(recovered.orElseThrow(), false);
        }
        requireNoPendingCleanup();
        byte[] oldKey = requireReadyKey();
        String oldKeyId = activeKeyId;
        String newKeyId = newKeyId();
        byte[] newKey = new byte[VaultCipher.KEY_BYTES];
        random.nextBytes(newKey);
        boolean committed = false;
        try {
            protector.store(newKeyId, newKey);
            VaultManagementReceipt receipt = execute(connection -> {
                VaultRepository.KeyState state = repository
                        .keyState(connection, true)
                        .orElseThrow(() -> new VaultException("Vault key state 不存在"));
                if (!state.activeKeyId().equals(oldKeyId) || state.previousKeyId() != null) {
                    throw new VaultException("Vault key state 已改变，请刷新后重试");
                }
                List<VaultRepository.StoredSecret> secrets = repository.listForUpdate(connection);
                credentialTransactions.reencrypt(connection, secrets, oldKey, newKey);
                Instant completedAt = clock.instant();
                repository.rotateKeyState(connection, newKeyId, oldKeyId, completedAt);
                VaultManagementReceipt result = new VaultManagementReceipt(
                        VaultManagementAction.MASTER_KEY_ROTATED, secrets.size(), completedAt);
                commands.record(connection, checkedIdentity, json.encode(result), completedAt);
                return result;
            });
            committed = true;
            replaceActiveKey(newKeyId, newKey);
            cleanupPreviousKey();
            return new VaultManagementResult(receipt, true);
        } catch (MasterKeyProtectionException failure) {
            throw new VaultException("系统凭据设施拒绝轮换 Vault 主密钥", failure);
        } finally {
            Arrays.fill(oldKey, (byte) 0);
            Arrays.fill(newKey, (byte) 0);
            if (!committed) {
                VaultKeyCleanup.deleteUnreferenced(protector, newKeyId);
            }
        }
    }

    /**
     * 危险重置：删除全部 Secret 并生成全新主密钥，所有旧 CredentialRef 立即失效。
     *
     * @param identity 幂等命令身份；expected revision 必须为 0
     * @return 被失效凭据数量与提交时间
     */
    public VaultManagementReceipt reset(CommandIdentity identity) {
        return changeListeners
                .afterConditionalChange(this, () -> resetLocked(identity), VaultManagementResult::changed)
                .receipt();
    }

    private VaultManagementResult resetLocked(CommandIdentity identity) {
        CommandIdentity checkedIdentity = VaultChecks.requireExpectedRevision(identity, 0);
        Optional<VaultManagementReceipt> recovered = recover(checkedIdentity, VaultManagementReceipt.class);
        if (recovered.isPresent()) {
            return new VaultManagementResult(recovered.orElseThrow(), false);
        }
        requireOpen();
        requireNoPendingCleanup();
        VaultRepository.KeyState current = execute(connection ->
                repository.keyState(connection, false).orElseThrow(() -> new VaultException("Vault key state 不存在")));
        String newKeyId = newKeyId();
        byte[] newKey = new byte[VaultCipher.KEY_BYTES];
        random.nextBytes(newKey);
        boolean committed = false;
        try {
            protector.store(newKeyId, newKey);
            VaultManagementReceipt receipt = execute(connection -> {
                VaultRepository.KeyState locked = repository
                        .keyState(connection, true)
                        .orElseThrow(() -> new VaultException("Vault key state 不存在"));
                if (!locked.activeKeyId().equals(current.activeKeyId()) || locked.previousKeyId() != null) {
                    throw new VaultException("Vault key state 已改变，请刷新后重试");
                }
                long invalidated = repository.count(connection);
                repository.deleteAll(connection);
                Instant completedAt = clock.instant();
                repository.rotateKeyState(connection, newKeyId, current.activeKeyId(), completedAt);
                VaultManagementReceipt result =
                        new VaultManagementReceipt(VaultManagementAction.VAULT_RESET, invalidated, completedAt);
                commands.record(connection, checkedIdentity, json.encode(result), completedAt);
                return result;
            });
            committed = true;
            replaceActiveKey(newKeyId, newKey);
            cleanupPreviousKey();
            return new VaultManagementResult(receipt, true);
        } catch (MasterKeyProtectionException failure) {
            throw new VaultException("系统凭据设施拒绝重置 Vault", failure);
        } finally {
            Arrays.fill(newKey, (byte) 0);
            if (!committed) {
                VaultKeyCleanup.deleteUnreferenced(protector, newKeyId);
            }
        }
    }

    /** 清零进程内主密钥；系统凭据包装与 H2 密文保持不变。 */
    @Override
    public void close() {
        changeListeners.afterConditionalChange(this, this::closeLocked, Boolean::booleanValue);
    }

    private boolean closeLocked() {
        if (closed) {
            return false;
        }
        closed = true;
        clearActiveKey();
        lockReason = VaultLockReason.CLOSED;
        return true;
    }

    private void initialize() {
        requireOpen();
        Optional<VaultRepository.KeyState> availableState = ensureKeyState();
        if (availableState.isEmpty()) {
            return;
        }
        VaultRepository.KeyState state = availableState.orElseThrow();
        try {
            Optional<byte[]> loaded = protector.load(state.activeKeyId());
            if (loaded.isEmpty()) {
                lock(VaultLockReason.MASTER_KEY_MISSING);
                return;
            }
            byte[] key = loaded.orElseThrow();
            try {
                if (key.length != VaultCipher.KEY_BYTES) {
                    lock(VaultLockReason.MASTER_KEY_INVALID);
                    return;
                }
                replaceActiveKey(state.activeKeyId(), key);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
            cleanupPreviousKey();
        } catch (MasterKeyProtectionException failure) {
            lock(VaultLockReason.SYSTEM_CREDENTIAL_UNAVAILABLE);
        }
    }

    private Optional<VaultRepository.KeyState> ensureKeyState() {
        Optional<VaultRepository.KeyState> existing = execute(connection -> repository.keyState(connection, false));
        if (existing.isPresent()) {
            return existing;
        }
        String keyId = newKeyId();
        byte[] key = new byte[VaultCipher.KEY_BYTES];
        random.nextBytes(key);
        boolean inserted = false;
        try {
            protector.store(keyId, key);
            execute(connection -> {
                repository.insertKeyState(connection, keyId, clock.instant());
                return null;
            });
            inserted = true;
            return Optional.of(new VaultRepository.KeyState(keyId, null));
        } catch (MasterKeyProtectionException failure) {
            lock(VaultLockReason.SYSTEM_CREDENTIAL_UNAVAILABLE);
            return Optional.empty();
        } finally {
            Arrays.fill(key, (byte) 0);
            if (!inserted) {
                VaultKeyCleanup.deleteUnreferenced(protector, keyId);
            }
        }
    }

    private void cleanupPreviousKey() {
        Optional<VaultRepository.KeyState> current = execute(connection -> repository.keyState(connection, false));
        String previousKeyId =
                current.map(VaultRepository.KeyState::previousKeyId).orElse(null);
        if (previousKeyId == null) {
            return;
        }
        try {
            protector.delete(previousKeyId);
            execute(connection -> {
                repository.clearPreviousKey(connection, previousKeyId, clock.instant());
                return null;
            });
        } catch (MasterKeyProtectionException ignored) {
            // 状态保留 previous key id，Diagnostics 会提示，后续 refresh 或轮换前会重试。
        }
    }

    private void requireNoPendingCleanup() {
        requireOpen();
        cleanupPreviousKey();
        VaultRepository.KeyState state = execute(connection ->
                repository.keyState(connection, false).orElseThrow(() -> new VaultException("Vault key state 不存在")));
        if (state.previousKeyId() != null) {
            throw new VaultException("旧 Vault 主密钥包装尚未清理，拒绝再次轮换");
        }
    }

    byte[] requireReadyKey() {
        requireReady();
        return activeKey.clone();
    }

    void requireReady() {
        requireOpen();
        if (!ready()) {
            throw new VaultException("Secret Vault 已锁定");
        }
    }

    private void replaceActiveKey(String keyId, byte[] key) {
        clearActiveKey();
        activeKey = key.clone();
        activeKeyId = keyId;
        lockReason = VaultLockReason.NONE;
    }

    private void clearActiveKey() {
        if (activeKey != null) {
            Arrays.fill(activeKey, (byte) 0);
            activeKey = null;
        }
        activeKeyId = null;
    }

    private void lock(VaultLockReason reason) {
        clearActiveKey();
        lockReason = Objects.requireNonNull(reason, "reason");
    }

    private boolean ready() {
        return !closed && activeKey != null && lockReason == VaultLockReason.NONE;
    }

    void requireOpen() {
        if (closed) {
            throw new VaultException("Secret Vault 已关闭");
        }
    }

    private <T> Optional<T> recover(CommandIdentity identity, Class<T> resultType) {
        return execute(
                connection -> commands.recover(connection, identity).map(payload -> json.decode(payload, resultType)));
    }

    String activeKeyId() {
        requireReady();
        return activeKeyId;
    }

    Instant currentTime() {
        return clock.instant();
    }

    private static String newKeyId() {
        return KEY_PREFIX + UUID.randomUUID();
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new VaultException("Secret Vault 事务失败", failure);
        }
    }
}
