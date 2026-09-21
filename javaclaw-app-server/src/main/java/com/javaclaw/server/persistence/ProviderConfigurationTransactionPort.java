package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.protocol.CanonicalJson;

/** 完整 Provider 配置在同一事务中校验版本、写入配置并保存脱敏回执的窄端口。 */
final class ProviderConfigurationTransactionPort {
    private final H2Transactions transactions;
    private final VersionedSettingsRepository versions = new VersionedSettingsRepository();
    private final IdempotentCommandStore commands = new IdempotentCommandStore();
    private final ProviderCredentialTransactionPort providers;
    private final CanonicalJson json;

    ProviderConfigurationTransactionPort(H2Database database, CanonicalJson json) {
        this(database, json, new ProviderCredentialTransactionPort(json));
    }

    ProviderConfigurationTransactionPort(
            H2Database database, CanonicalJson json, ProviderCredentialTransactionPort providers) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    Optional<ProviderEndpoint> current(ProviderConfiguration configuration) {
        return execute(connection -> requireCurrent(connection, configuration));
    }

    Optional<ProviderConfigurationResult> recover(CommandIdentity identity) {
        return execute(connection -> commands.recover(connection, identity)
                .map(payload -> json.decode(payload, ProviderConfigurationResult.class)));
    }

    ProviderConfigurationResult commit(
            CommandIdentity identity, ProviderConfiguration configuration, ProviderConfigurationResult result) {
        return execute(connection -> {
            var recovered = commands.recover(connection, identity);
            if (recovered.isPresent()) {
                return json.decode(recovered.orElseThrow(), ProviderConfigurationResult.class);
            }
            requireCurrent(connection, configuration);
            providers.insert(connection, result.provider());
            commands.record(
                    connection, identity, json.encode(result), result.provider().updatedAt());
            return result;
        });
    }

    ProviderConfigurationResult insert(
            Connection connection,
            ProviderConfiguration configuration,
            ProviderEndpoint candidate,
            Optional<CredentialMetadata> credential)
            throws Exception {
        Optional<ProviderEndpoint> current = requireCurrent(connection, configuration);
        if (configuration.credentialChange() == ProviderCredentialChange.CLEAR) {
            var reference = current.flatMap(endpoint -> endpoint.spec().credential())
                    .orElseThrow(() -> PersistenceException.revisionConflict("Provider 凭据绑定已改变"));
            providers.requireExclusiveReference(connection, configuration.providerId(), reference);
        }
        providers.insert(connection, candidate);
        return new ProviderConfigurationResult(candidate, credential);
    }

    private Optional<ProviderEndpoint> requireCurrent(Connection connection, ProviderConfiguration configuration)
            throws Exception {
        Optional<ProviderEndpoint> current = versions.latest(
                        connection, VersionedSettingsRepository.Table.PROVIDER, configuration.providerId(), true)
                .map(this::decode);
        long actual = current.map(ProviderEndpoint::revision).orElse(0L);
        if (actual != configuration.expectedRevision()) {
            throw PersistenceException.revisionConflict("Provider revision 已改变");
        }
        if (current.filter(endpoint -> endpoint.lifecycle() == ProviderLifecycle.ARCHIVED)
                .isPresent()) {
            throw PersistenceException.invalidRequest("已归档 Provider 不能修改配置");
        }
        return current;
    }

    private ProviderEndpoint decode(VersionedSettingsRepository.StoredVersion stored) {
        return new ProviderEndpoint(
                stored.id(),
                stored.revision(),
                ProviderLifecycle.valueOf(stored.lifecycle()),
                json.decode(stored.payload(), ProviderEndpointSpec.class),
                stored.createdAt(),
                stored.updatedAt());
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Provider 完整配置事务失败", failure);
        }
    }
}
