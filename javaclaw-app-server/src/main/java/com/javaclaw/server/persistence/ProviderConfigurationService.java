package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;

/**
 * 完整 Provider 配置的原子提交边界；预览不调用本服务，也不创建半成品连接。
 *
 * <p>沿用 Provider 写锁、候选 Adapter 预构造和提交后激活协议。凭据变更通过专用 Vault 事务端口提交， 配置和命令回执不会先于 Secret 单独落库；任何返回路径都会清零调用方的临时明文字节。
 */
public final class ProviderConfigurationService {
    private final ProviderService providers;
    private final ProviderCredentialMutationPort vault;
    private final Function<CredentialRef, Optional<CredentialMetadata>> metadata;
    private final ProviderConfigurationTransactionPort transactions;
    private final Clock clock;

    /**
     * 创建完整配置应用服务。
     *
     * @param database Provider 与 Vault 共用的数据库
     * @param providers 现有 Provider 写入协调器
     * @param vault Provider 凭据原子变更端口
     * @param metadata 只读脱敏凭据元数据边界，不得返回 Secret
     * @param json 规范 JSON 编解码器
     * @param clock 平台时钟
     */
    public ProviderConfigurationService(
            H2Database database,
            ProviderService providers,
            ProviderCredentialMutationPort vault,
            Function<CredentialRef, Optional<CredentialMetadata>> metadata,
            CanonicalJson json,
            Clock clock) {
        this(providers, vault, metadata, new ProviderConfigurationTransactionPort(database, json), clock);
    }

    ProviderConfigurationService(
            ProviderService providers,
            ProviderCredentialMutationPort vault,
            Function<CredentialRef, Optional<CredentialMetadata>> metadata,
            ProviderConfigurationTransactionPort transactions,
            Clock clock) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 创建或更新完整配置；只有最终保存动作调用，不能用于临时模型目录发现。
     *
     * @param identity 固定保存命令身份，expected revision 必须与配置一致
     * @param configuration 不含 Secret 和任意 CredentialRef 的完整配置意图
     * @param plaintext REPLACE 的临时明文；其他动作必须为空，返回前始终清零
     * @return 已提交或从原命令恢复的 Provider 及脱敏凭据元数据
     */
    public ProviderConfigurationResult save(
            CommandIdentity identity, ProviderConfiguration configuration, byte[] plaintext) {
        try {
            CommandIdentity checked = requireIdentity(identity);
            ProviderConfiguration request = Objects.requireNonNull(configuration, "configuration");
            if (checked.expectedRevision() != request.expectedRevision()) {
                throw PersistenceException.invalidRequest("完整配置 expected revision 不一致");
            }
            return providers.coordinateSerialCommand(checked, () -> saveSerially(checked, request, plaintext));
        } finally {
            clear(plaintext);
        }
    }

    /**
     * 读取已提交命令结果；必须在解封 Secret 之前调用。
     *
     * @param identity 原保存方法、幂等键、expected revision 与原请求摘要
     * @return 已提交结果；为空只表示暂未查到回执，不证明原请求未在执行
     */
    public Optional<ProviderConfigurationResult> recover(CommandIdentity identity) {
        CommandIdentity checked = requireIdentity(identity);
        Optional<ProviderConfigurationResult> recovered = transactions.recover(checked);
        if (recovered
                .filter(result -> result.provider().revision() - 1 != checked.expectedRevision())
                .isPresent()) {
            throw PersistenceException.idempotencyConflict("配置回执的 expected revision 不匹配");
        }
        return recovered;
    }

    private ProviderConfigurationResult saveSerially(
            CommandIdentity identity, ProviderConfiguration configuration, byte[] plaintext) {
        Optional<ProviderConfigurationResult> recovered = recover(identity);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        validateSecretAction(configuration, plaintext);
        Optional<ProviderEndpoint> current = transactions.current(configuration);
        Optional<CredentialRef> reference =
                current.flatMap(endpoint -> endpoint.spec().credential());
        requireCredentialRevision(configuration, reference);
        if (configuration.credentialChange() == ProviderCredentialChange.KEEP) {
            return keep(identity, configuration, current, reference);
        }
        return mutateCredential(identity, configuration, current, reference, plaintext);
    }

    private ProviderConfigurationResult keep(
            CommandIdentity identity,
            ProviderConfiguration configuration,
            Optional<ProviderEndpoint> current,
            Optional<CredentialRef> reference) {
        requireRetainedConnection(configuration, current, reference);
        Optional<CredentialMetadata> credential = reference.map(value -> requireMetadata(configuration, value));
        ProviderEndpoint candidate = candidate(configuration, current, reference);
        ProviderConfigurationResult result = new ProviderConfigurationResult(candidate, credential);
        return providers.coordinatePreparedMutation(
                candidate,
                configuration.expectedRevision(),
                () -> reference.isPresent()
                        ? vault.retained(
                                reference.orElseThrow(),
                                configuration.credentialExpectedRevision(),
                                () -> transactions.commit(identity, configuration, result))
                        : transactions.commit(identity, configuration, result));
    }

    private ProviderConfigurationResult mutateCredential(
            CommandIdentity identity,
            ProviderConfiguration configuration,
            Optional<ProviderEndpoint> current,
            Optional<CredentialRef> reference,
            byte[] plaintext) {
        ProviderCredentialMutationPort.Prepared candidateSecret;
        try {
            candidateSecret = configuration.credentialChange() == ProviderCredentialChange.REPLACE
                    ? vault.prepare(reference, configuration.credentialExpectedRevision(), plaintext)
                    : vault.prepareClear(reference.orElseThrow(), configuration.credentialExpectedRevision());
        } finally {
            clear(plaintext);
        }
        try (ProviderCredentialMutationPort.Prepared prepared = candidateSecret) {
            Optional<CredentialMetadata> credential = configuration.credentialChange() == ProviderCredentialChange.CLEAR
                    ? Optional.empty()
                    : Optional.of(prepared.metadata());
            ProviderEndpoint candidate =
                    candidate(configuration, current, credential.map(CredentialMetadata::reference));
            if (configuration.credentialChange() == ProviderCredentialChange.CLEAR) {
                return commitCredential(identity, configuration, candidate, credential, prepared);
            }
            return vault.expose(
                    prepared, () -> commitCredential(identity, configuration, candidate, credential, prepared));
        }
    }

    private ProviderConfigurationResult commitCredential(
            CommandIdentity identity,
            ProviderConfiguration configuration,
            ProviderEndpoint candidate,
            Optional<CredentialMetadata> credential,
            ProviderCredentialMutationPort.Prepared prepared) {
        return providers.coordinatePreparedMutation(
                candidate,
                configuration.expectedRevision(),
                () -> vault.commit(
                        identity,
                        prepared,
                        ProviderConfigurationResult.class,
                        connection -> transactions.insert(connection, configuration, candidate, credential)));
    }

    private CredentialMetadata requireMetadata(ProviderConfiguration configuration, CredentialRef reference) {
        CredentialMetadata current =
                metadata.apply(reference).orElseThrow(() -> PersistenceException.invalidRequest("Provider 凭据不可用"));
        if (!current.reference().equals(reference)
                || current.revision() != configuration.credentialExpectedRevision()) {
            throw PersistenceException.revisionConflict("Provider 凭据 revision 已改变");
        }
        return current;
    }

    private ProviderEndpoint candidate(
            ProviderConfiguration configuration,
            Optional<ProviderEndpoint> current,
            Optional<CredentialRef> credential) {
        ProviderEndpointSpec spec = configuration.connection().toEndpointSpec(configuration.models(), credential);
        if (configuration.lifecycle() == ProviderLifecycle.ARCHIVED) {
            throw PersistenceException.invalidRequest("归档 Provider 必须使用独立归档命令");
        }
        if (configuration.lifecycle() == ProviderLifecycle.ACTIVE
                && (spec.models().isEmpty()
                        || spec.authentication() == ProviderAuthentication.API_KEY && credential.isEmpty())) {
            throw PersistenceException.invalidRequest("启用模型服务前必须配置模型和所需凭据");
        }
        Instant now = clock.instant();
        return new ProviderEndpoint(
                configuration.providerId(),
                Math.addExact(configuration.expectedRevision(), 1),
                configuration.lifecycle(),
                spec,
                current.map(ProviderEndpoint::createdAt).orElse(now),
                now);
    }

    private static void requireCredentialRevision(
            ProviderConfiguration configuration, Optional<CredentialRef> reference) {
        if (reference.isPresent() != (configuration.credentialExpectedRevision() > 0)) {
            throw PersistenceException.revisionConflict("凭据 expected revision 与绑定状态不一致");
        }
        if (configuration.credentialChange() == ProviderCredentialChange.CLEAR && reference.isEmpty()) {
            throw PersistenceException.invalidRequest("模型服务尚未绑定可清除的凭据");
        }
    }

    private static void validateSecretAction(ProviderConfiguration configuration, byte[] plaintext) {
        boolean replacement = configuration.credentialChange() == ProviderCredentialChange.REPLACE;
        if (replacement != (plaintext != null && plaintext.length > 0)) {
            throw PersistenceException.invalidRequest("凭据变更动作与 Secret 输入不一致");
        }
        ProviderAuthentication authentication = configuration.connection().authentication();
        if (replacement && authentication != ProviderAuthentication.API_KEY) {
            throw PersistenceException.invalidRequest("只有 API Key 鉴权可以写入或轮换密钥");
        }
        if (configuration.credentialChange() == ProviderCredentialChange.CLEAR
                && authentication != ProviderAuthentication.NONE) {
            throw PersistenceException.invalidRequest("完整配置清除密钥时必须明确改为无鉴权");
        }
    }

    private static void requireRetainedConnection(
            ProviderConfiguration configuration,
            Optional<ProviderEndpoint> current,
            Optional<CredentialRef> reference) {
        if (reference.isEmpty()) {
            return;
        }
        ProviderEndpointSpec before = current.orElseThrow().spec();
        if (before.adapter() != configuration.connection().adapter()
                || !before.baseUri().equals(configuration.connection().baseUri())) {
            throw PersistenceException.invalidRequest("修改服务地址或接口协议时必须重新提供 API Key");
        }
    }

    private static CommandIdentity requireIdentity(CommandIdentity identity) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (!ProviderConfigurationRpcContracts.SAVE_METHOD.equals(checked.method())) {
            throw PersistenceException.invalidRequest("不是完整 Provider 配置保存命令");
        }
        return checked;
    }

    private static void clear(byte[] plaintext) {
        if (plaintext != null) {
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
