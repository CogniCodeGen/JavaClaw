package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.CredentialVaultPort;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.extension.spi.PrivateNetworkGrantPort;
import com.javaclaw.extension.spi.ScheduleLifecyclePort;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.ScheduledCommand;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ProtocolException;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ExtensionJobService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.persistence.InputRequestService;
import com.javaclaw.server.persistence.PermissionProfileService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuiltinExtensionHostScheduleValidationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private CoreCommandService core;
    private PermissionProfileService profiles;
    private BuiltinExtensionRuntimePorts ports;

    @BeforeEach
    void setUp() {
        json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, CLOCK);
        profiles = new PermissionProfileService(database, json, CLOCK);
        profiles.installStandardProfile();
        ports = ports(database);
    }

    @Test
    void scheduledActionRechecksFrozenSchemaRevisionAndPayloadBeforeHandler() throws Exception {
        TestScheduleBundle bundle = new TestScheduleBundle();
        Workspace workspace = createWorkspace();

        try (BuiltinExtensionHost host = BuiltinExtensionHost.start(List.of(bundle), core, profiles, ports)) {
            ScheduleTargetCatalogPort.ActionOption option =
                    host.actions(workspace.id()).getFirst();
            ScheduledCommand valid =
                    scheduled(workspace, json.encode(Map.of("scope", "workspace")), 7, option.schemaHash());

            assertEquals(
                    0,
                    host.scheduledCommand(call(valid), valid, new CancellationSource())
                            .revision());
            assertRejected(host, scheduled(workspace, valid.payload(), 6, option.schemaHash()));
            assertRejected(host, scheduled(workspace, valid.payload(), 7, "f".repeat(64)));
            assertPayloadRejected(host, scheduled(workspace, json.encode(Map.of()), 7, option.schemaHash()));
            assertPayloadRejected(
                    host,
                    scheduled(
                            workspace,
                            json.encode(Map.of("scope", "workspace", "extra", true)),
                            7,
                            option.schemaHash()));
        }
    }

    private BuiltinExtensionRuntimePorts ports(H2Database database) {
        return new BuiltinExtensionRuntimePorts(
                CLOCK,
                new CanonicalExtensionPayloadCodec(json),
                new H2ManagedExtensionStore(database, CLOCK),
                (command, cancellation) -> {
                    throw new AssertionError("turn orchestration must not be used");
                },
                (workspaceId, profile, cancellation) -> {
                    throw new AssertionError("automation execution policy must not be used");
                },
                new InputRequestService(database, json, CLOCK),
                new ExtensionJobService(database, json, CLOCK),
                (workspaceId, threadId, itemId, verbatim) -> false,
                workspaceId -> reference -> {
                    throw new IllegalArgumentException("Attachment evidence is unavailable");
                },
                emptyCredentials(),
                emptyPrivateNetworkGrants(),
                invocation -> {
                    throw new AssertionError("isolated service must not be used");
                },
                EmbeddingPort.unavailable(),
                AutomationStepPort.unavailable(),
                ScheduledCommandPort.unavailable(),
                ScheduleLifecyclePort.unavailable(),
                new ExtensionCatalogRepository(database, json, CLOCK));
    }

    private Workspace createWorkspace() {
        Map<String, String> payload = Map.of("name", "schedule");
        return core.createWorkspace(
                CommandIdentity.from(
                        "workspace/create", new WriteCommand("schedule-workspace", 0, json.encode(payload)), json),
                "schedule",
                temporaryDirectory.resolve("workspace").toAbsolutePath());
    }

    private ScheduledCommand scheduled(
            Workspace workspace, CanonicalPayload payload, long revision, String schemaHash) {
        return new ScheduledCommand(
                workspace.id(),
                TestScheduleBundle.ID,
                TestScheduleBundle.OPERATION,
                payload,
                "occurrence-1",
                revision,
                Optional.of(schemaHash),
                Optional.empty());
    }

    private ExtensionRpcContracts.CallPayload call(ScheduledCommand command) {
        return new ExtensionRpcContracts.CallPayload(
                command.extensionId(),
                command.workspaceId(),
                Optional.empty(),
                Optional.empty(),
                command.operation(),
                command.payload());
    }

    private void assertRejected(BuiltinExtensionHost host, ScheduledCommand command) {
        assertThrows(
                IllegalArgumentException.class,
                () -> host.scheduledCommand(call(command), command, new CancellationSource()));
    }

    private void assertPayloadRejected(BuiltinExtensionHost host, ScheduledCommand command) {
        assertThrows(
                ProtocolException.class, () -> host.scheduledCommand(call(command), command, new CancellationSource()));
    }

    private static CredentialVaultPort emptyCredentials() {
        return new CredentialVaultPort() {
            @Override
            public Optional<CredentialMetadata> metadata(CredentialRef reference) {
                return Optional.empty();
            }

            @Override
            public List<CredentialMetadata> listMetadata(String namespace) {
                return List.of();
            }
        };
    }

    private static PrivateNetworkGrantPort emptyPrivateNetworkGrants() {
        return new PrivateNetworkGrantPort() {
            @Override
            public List<PrivateNetworkGrant> available(WorkspaceId workspaceId, PrivateNetworkPurpose purpose) {
                return List.of();
            }

            @Override
            public PrivateNetworkGrant requireBindable(
                    PrivateNetworkGrantRef reference,
                    WorkspaceId workspaceId,
                    PrivateNetworkPurpose purpose,
                    URI origin) {
                throw new IllegalArgumentException("Private-network grant evidence is unavailable");
            }
        };
    }

    private final class TestScheduleBundle implements ExtensionBundle {
        private static final String ID = "test.schedule";
        private static final String OPERATION = "refresh";

        @Override
        public ExtensionDescriptor descriptor() {
            return new ExtensionDescriptor(
                    new ExtensionId(ID),
                    ID,
                    "5.0.0",
                    1,
                    Set.of(ContributionKind.COMMAND, ContributionKind.SCHEDULABLE_ACTION),
                    new ExtensionRequirements(
                            ExtensionTrust.BUILT_IN,
                            com.javaclaw.extension.spi.ExtensionAvailability.OPTIONAL,
                            2,
                            profiles.require(PermissionProfileService.STANDARD_PROFILE_ID, 1)));
        }

        @Override
        public List<ExtensionContribution> start(ExtensionContext context) {
            ScheduleTargetCatalogPort.ActionField field = new ScheduleTargetCatalogPort.ActionField(
                    "scope", "范围", ScheduleTargetCatalogPort.ScalarType.STRING, true);
            return List.of(
                    new ExtensionContributions.Command(
                            "command",
                            Set.of(OPERATION),
                            (request, invocation) -> new ExtensionResponse(json.encode(Map.of("ok", true)), 0)),
                    new ExtensionContributions.SchedulableAction("schedule", OPERATION, "刷新", true, List.of(field), 7));
        }

        @Override
        public void close() {}
    }
}
