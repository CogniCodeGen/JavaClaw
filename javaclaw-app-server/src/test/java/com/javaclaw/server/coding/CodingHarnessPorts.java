package com.javaclaw.server.coding;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.CredentialVaultPort;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.PrivateNetworkGrantPort;
import com.javaclaw.extension.spi.ScheduleLifecyclePort;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.server.extension.BuiltinExtensionRuntimePorts;
import com.javaclaw.server.extension.CanonicalExtensionPayloadCodec;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ExtensionJobService;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.persistence.InputRequestService;

/** 为真实 Coding Host 装配持久端口；无关能力一旦被调用即令验收失败。 */
final class CodingHarnessPorts {
    private CodingHarnessPorts() {}

    static BuiltinExtensionRuntimePorts create(CodingTestFixture fixture, ExtensionCatalogRepository catalog) {
        return new BuiltinExtensionRuntimePorts(
                fixture.clock,
                new CanonicalExtensionPayloadCodec(fixture.json),
                new H2ManagedExtensionStore(fixture.database, fixture.clock),
                (command, cancellation) -> {
                    throw new AssertionError("unexpected automation Turn");
                },
                (workspace, profile, cancellation) -> {
                    throw new AssertionError("unexpected automation policy");
                },
                new InputRequestService(fixture.database, fixture.json, fixture.clock),
                new ExtensionJobService(fixture.database, fixture.json, fixture.clock),
                (workspace, thread, item, text) -> false,
                workspace -> reference -> {
                    throw new AssertionError("unexpected attachment query");
                },
                new NoCredentials(),
                new NoPrivateNetwork(),
                invocation -> {
                    throw new AssertionError("unexpected isolated service");
                },
                EmbeddingPort.unavailable(),
                AutomationStepPort.unavailable(),
                ScheduledCommandPort.unavailable(),
                ScheduleLifecyclePort.unavailable(),
                catalog,
                com.javaclaw.extension.spi.ConversationEvidencePort.unavailable(),
                owner -> com.javaclaw.extension.spi.ScheduleDefinitionBindingPort.unavailable());
    }

    private static final class NoCredentials implements CredentialVaultPort {
        @Override
        public Optional<CredentialMetadata> metadata(CredentialRef reference) {
            throw new AssertionError("unexpected credential access");
        }

        @Override
        public List<CredentialMetadata> listMetadata(String namespace) {
            throw new AssertionError("unexpected credential access");
        }
    }

    private static final class NoPrivateNetwork implements PrivateNetworkGrantPort {
        @Override
        public List<PrivateNetworkGrant> available(WorkspaceId workspace, PrivateNetworkPurpose purpose) {
            throw new AssertionError("unexpected private network access");
        }

        @Override
        public PrivateNetworkGrant requireBindable(
                PrivateNetworkGrantRef reference, WorkspaceId workspace, PrivateNetworkPurpose purpose, URI origin) {
            throw new AssertionError("unexpected private network access");
        }
    }
}
