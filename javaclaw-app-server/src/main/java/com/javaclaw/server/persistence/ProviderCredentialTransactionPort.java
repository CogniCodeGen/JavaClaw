package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.protocol.CanonicalJson;

/** Provider 版本表在复合凭据事务内使用的窄 package-private 端口。 */
final class ProviderCredentialTransactionPort {
    private final VersionedSettingsRepository versions = new VersionedSettingsRepository();
    private final CanonicalJson json;
    private final FailureProbe failureProbe;

    ProviderCredentialTransactionPort(CanonicalJson json) {
        this(json, () -> {});
    }

    ProviderCredentialTransactionPort(CanonicalJson json, FailureProbe failureProbe) {
        this.json = Objects.requireNonNull(json, "json");
        this.failureProbe = Objects.requireNonNull(failureProbe, "failureProbe");
    }

    ProviderEndpoint requireCurrent(Connection connection, String id, long expectedRevision) throws Exception {
        ProviderEndpoint current = versions.latest(connection, VersionedSettingsRepository.Table.PROVIDER, id, true)
                .map(this::decode)
                .orElseThrow(() -> PersistenceException.invalidRequest("Provider 不存在"));
        if (current.revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Provider revision 已改变");
        }
        return current;
    }

    void insert(Connection connection, ProviderEndpoint candidate) throws Exception {
        var previous = versions.latest(connection, VersionedSettingsRepository.Table.PROVIDER, candidate.id(), true)
                .map(this::decode);
        failureProbe.beforeInsert();
        versions.insert(connection, VersionedSettingsRepository.Table.PROVIDER, stored(candidate));
        new ProviderContextRepository().inherit(connection, previous, candidate);
    }

    void requireExclusiveReference(Connection connection, String ownerId, CredentialRef reference) throws Exception {
        List<ProviderEndpoint> references =
                versions.listLatest(connection, VersionedSettingsRepository.Table.PROVIDER).stream()
                        .map(this::decode)
                        .filter(endpoint -> endpoint.spec()
                                .credential()
                                .filter(reference::equals)
                                .isPresent())
                        .toList();
        if (references.size() != 1 || !references.getFirst().id().equals(ownerId)) {
            throw PersistenceException.invalidRequest("CredentialRef 仍被其他权威 Provider 引用");
        }
    }

    private VersionedSettingsRepository.StoredVersion stored(ProviderEndpoint endpoint) {
        return new VersionedSettingsRepository.StoredVersion(
                endpoint.id(),
                endpoint.revision(),
                endpoint.lifecycle().name(),
                json.encode(endpoint.spec()),
                endpoint.createdAt(),
                endpoint.updatedAt());
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

    @FunctionalInterface
    interface FailureProbe {
        void beforeInsert();
    }
}
