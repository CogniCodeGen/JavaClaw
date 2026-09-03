package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.protocol.CanonicalJson;

/** Provider 新版本、Vault 密文和幂等回执的原子复合应用服务。 */
public final class ProviderCredentialService {
    private final ProviderService providers;
    private final ProviderCredentialMutationPort vault;
    private final ProviderCredentialTransactionPort transactions;
    private final Clock clock;

    /**
     * 创建复合服务。
     *
     * @param providers Provider 版本与 Adapter 预构造协调器
     * @param vault Provider Secret 原子变更端口
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public ProviderCredentialService(
            ProviderService providers, ProviderCredentialMutationPort vault, CanonicalJson json, Clock clock) {
        this(providers, Objects.requireNonNull(vault, "vault"), new ProviderCredentialTransactionPort(json), clock);
    }

    ProviderCredentialService(
            ProviderService providers,
            ProviderCredentialMutationPort vault,
            ProviderCredentialTransactionPort transactions,
            Clock clock) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 首次绑定或轮换 Provider Secret，并为同一 Provider 创建新版本。
     *
     * <p>方法接管 {@code plaintext} 的清零责任；无论预构造、事务或激活是否失败，返回前都清零该数组。
     *
     * @param identity 复合写命令身份，expected revision 为 Provider revision
     * @param providerId Provider 标识
     * @param providerExpectedRevision Provider 当前版本
     * @param credentialExpectedRevision 未绑定时为 0，轮换时为当前凭据版本
     * @param plaintext 已解封的临时 Secret 字节
     * @return Provider 与凭据脱敏结果
     */
    public ProviderCredentialBinding set(
            CommandIdentity identity,
            String providerId,
            long providerExpectedRevision,
            long credentialExpectedRevision,
            byte[] plaintext) {
        CommandIdentity checked = requireProviderRevision(identity, providerExpectedRevision);
        return providers.coordinateSerialCommand(
                checked,
                () -> setSerially(
                        checked, providerId, providerExpectedRevision, credentialExpectedRevision, plaintext));
    }

    private ProviderCredentialBinding setSerially(
            CommandIdentity identity,
            String providerId,
            long providerExpectedRevision,
            long credentialExpectedRevision,
            byte[] plaintext) {
        Optional<ProviderCredentialBinding> replay = recoverSet(identity);
        if (replay.isPresent()) {
            clear(plaintext);
            return replay.orElseThrow();
        }
        ProviderEndpoint current;
        ProviderCredentialMutationPort.Prepared candidateSecret;
        try {
            current = current(providerId, providerExpectedRevision);
            candidateSecret = vault.prepare(current.spec().credential(), credentialExpectedRevision, plaintext);
        } finally {
            clear(plaintext);
        }
        try (ProviderCredentialMutationPort.Prepared prepared = candidateSecret) {
            ProviderEndpoint candidate = candidate(
                    current,
                    withCredential(
                            current.spec(), Optional.of(prepared.metadata().reference())),
                    current.lifecycle());
            ProviderCredentialMutationPort.Prepared owned = prepared;
            return vault.expose(
                    owned,
                    () -> providers.coordinatePreparedMutation(
                            candidate,
                            providerExpectedRevision,
                            () -> commitSet(identity, current, candidate, credentialExpectedRevision, owned)));
        }
    }

    /**
     * 原子解除 Provider 引用并永久清除对应 Vault Secret。
     *
     * <p>解除凭据会把 Provider 新版本一并切换为 DISABLED，保证 API_KEY 端点不会留下表面启用但必然无法调用的状态。
     *
     * @param identity 复合写命令身份，expected revision 为 Provider revision
     * @param providerId Provider 标识
     * @param providerExpectedRevision Provider 当前版本
     * @param reference 当前 Provider 凭据引用
     * @param credentialExpectedRevision 凭据当前版本
     * @return Provider 新版本与清除回执
     */
    public ProviderCredentialClearResult clear(
            CommandIdentity identity,
            String providerId,
            long providerExpectedRevision,
            CredentialRef reference,
            long credentialExpectedRevision) {
        CommandIdentity checked = requireProviderRevision(identity, providerExpectedRevision);
        return providers.coordinateSerialCommand(
                checked,
                () -> clearSerially(
                        checked, providerId, providerExpectedRevision, reference, credentialExpectedRevision));
    }

    private ProviderCredentialClearResult clearSerially(
            CommandIdentity identity,
            String providerId,
            long providerExpectedRevision,
            CredentialRef reference,
            long credentialExpectedRevision) {
        Optional<ProviderCredentialClearResult> replay = recoverClear(identity);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        ProviderEndpoint current = current(providerId, providerExpectedRevision);
        requireBound(current, reference);
        try (ProviderCredentialMutationPort.Prepared prepared =
                vault.prepareClear(reference, credentialExpectedRevision)) {
            ProviderEndpoint candidate =
                    candidate(current, withCredential(current.spec(), Optional.empty()), ProviderLifecycle.DISABLED);
            return providers.coordinatePreparedMutation(
                    candidate, providerExpectedRevision, () -> commitClear(identity, current, candidate, prepared));
        }
    }

    /** @return 解封前可恢复的绑定或轮换结果 */
    public Optional<ProviderCredentialBinding> recoverSet(CommandIdentity identity) {
        return vault.recover(identity, ProviderCredentialBinding.class);
    }

    /** @return 可恢复的解除与清除结果 */
    public Optional<ProviderCredentialClearResult> recoverClear(CommandIdentity identity) {
        return vault.recover(identity, ProviderCredentialClearResult.class);
    }

    private ProviderCredentialBinding commitSet(
            CommandIdentity identity,
            ProviderEndpoint expected,
            ProviderEndpoint candidate,
            long credentialExpectedRevision,
            ProviderCredentialMutationPort.Prepared prepared) {
        return vault.commit(identity, prepared, ProviderCredentialBinding.class, connection -> {
            ProviderEndpoint locked = transactions.requireCurrent(connection, expected.id(), expected.revision());
            requireSameCredentialState(locked, expected, credentialExpectedRevision);
            transactions.insert(connection, candidate);
            return new ProviderCredentialBinding(candidate, prepared.metadata());
        });
    }

    private ProviderCredentialClearResult commitClear(
            CommandIdentity identity,
            ProviderEndpoint expected,
            ProviderEndpoint candidate,
            ProviderCredentialMutationPort.Prepared prepared) {
        return vault.commit(identity, prepared, ProviderCredentialClearResult.class, connection -> {
            ProviderEndpoint locked = transactions.requireCurrent(connection, expected.id(), expected.revision());
            requireBound(locked, prepared.metadata().reference());
            transactions.requireExclusiveReference(
                    connection, expected.id(), prepared.metadata().reference());
            transactions.insert(connection, candidate);
            CredentialClearReceipt receipt = new CredentialClearReceipt(
                    prepared.metadata().reference(),
                    prepared.metadata().revision(),
                    prepared.metadata().updatedAt());
            return new ProviderCredentialClearResult(candidate, receipt);
        });
    }

    private ProviderEndpoint current(String providerId, long expectedRevision) {
        ProviderEndpoint current = providers.requireLatestForMutation(providerId);
        if (current.revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Provider revision 已改变");
        }
        if (current.lifecycle() == ProviderLifecycle.ARCHIVED) {
            throw PersistenceException.invalidRequest("已归档 Provider 不能变更 Secret");
        }
        return current;
    }

    private ProviderEndpoint candidate(
            ProviderEndpoint current, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        Instant now = clock.instant();
        return new ProviderEndpoint(
                current.id(), Math.addExact(current.revision(), 1), lifecycle, spec, current.createdAt(), now);
    }

    private static void requireSameCredentialState(
            ProviderEndpoint locked, ProviderEndpoint expected, long credentialExpectedRevision) {
        if (!locked.spec().credential().equals(expected.spec().credential())) {
            throw PersistenceException.revisionConflict("Provider CredentialRef 已改变");
        }
        boolean creating = locked.spec().credential().isEmpty();
        if (creating != (credentialExpectedRevision == 0)) {
            throw PersistenceException.revisionConflict("CredentialRef revision 与绑定状态不一致");
        }
    }

    private static void requireBound(ProviderEndpoint provider, CredentialRef reference) {
        if (provider.spec().credential().filter(reference::equals).isEmpty()) {
            throw PersistenceException.revisionConflict("Provider CredentialRef 已改变");
        }
    }

    private static CommandIdentity requireProviderRevision(CommandIdentity identity, long expectedRevision) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (checked.expectedRevision() != expectedRevision || expectedRevision < 1) {
            throw PersistenceException.invalidRequest("复合命令的 Provider expected revision 不一致");
        }
        return checked;
    }

    private static ProviderEndpointSpec withCredential(
            ProviderEndpointSpec source, Optional<CredentialRef> credential) {
        return new ProviderEndpointSpec(
                source.displayName(),
                source.adapter(),
                source.baseUri(),
                source.authentication(),
                source.models(),
                credential,
                source.timeout(),
                source.maximumRetries(),
                source.options());
    }

    private static void clear(byte[] plaintext) {
        if (plaintext != null) {
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
