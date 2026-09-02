package com.javaclaw.extension.spi;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

final class SpiFixtures {
    static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SpiFixtures() {}

    static CanonicalPayload payload() {
        return new CanonicalPayload("{\"value\":1}");
    }

    static PermissionProfile permissions() {
        return new PermissionProfile(
                "extension-test",
                2,
                new FilePermission(List.of(Path.of("/workspace")), List.of(), false, false),
                new NetworkPermission(Set.of("example.com"), Set.of(443), true),
                new ProcessPermission(Set.of("java"), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of("read"), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(1_024, 512, 2, 8));
    }

    static ExtensionDescriptor descriptor() {
        return new ExtensionDescriptor(
                new ExtensionId("com.javaclaw.test"),
                "Test",
                "1.0.0",
                2,
                Set.of(ContributionKind.QUERY),
                new ExtensionRequirements(ExtensionTrust.BUILT_IN, ExtensionAvailability.OPTIONAL, 2, permissions()));
    }

    static ToolDescriptor tool(String producer, String name, long revision) {
        return new ToolDescriptor(
                new ToolIdentity(producer, name, revision),
                "读取测试数据",
                payload(),
                payload(),
                ToolRisk.READ_ONLY,
                Set.of("test"));
    }

    static TurnBudget budget() {
        return new TurnBudget(100, 50, 2, 1, Duration.ofSeconds(30));
    }

    static ExtensionExecutionContext executionContext() {
        ManagedExtensionStore store = new EmptyManagedStore();
        return new ExtensionExecutionContext(
                descriptor(),
                WorkspaceId.random(),
                permissions(),
                new CancellationSource(),
                CLOCK,
                store,
                (command, cancellation) -> null,
                (workspaceId, profile, cancellation) -> executionSnapshot(profile),
                ScheduleTargetCatalogPort.unavailable(),
                new EmptyInputPort(),
                new EmptyJobPort(),
                (workspaceId, threadId, itemId, verbatim) -> false,
                reference -> {
                    throw new IllegalArgumentException("Attachment evidence is unavailable");
                },
                new CredentialVaultPort() {
                    @Override
                    public java.util.Optional<com.javaclaw.api.CredentialMetadata> metadata(
                            com.javaclaw.api.CredentialRef reference) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.List<com.javaclaw.api.CredentialMetadata> listMetadata(String namespace) {
                        return java.util.List.of();
                    }
                },
                new PrivateNetworkGrantPort() {
                    @Override
                    public java.util.List<com.javaclaw.api.PrivateNetworkGrant> available(
                            WorkspaceId workspaceId, com.javaclaw.api.PrivateNetworkPurpose purpose) {
                        return java.util.List.of();
                    }

                    @Override
                    public com.javaclaw.api.PrivateNetworkGrant requireBindable(
                            com.javaclaw.api.PrivateNetworkGrantRef reference,
                            WorkspaceId workspaceId,
                            com.javaclaw.api.PrivateNetworkPurpose purpose,
                            java.net.URI origin) {
                        throw new IllegalArgumentException("Private-network grant evidence is unavailable");
                    }
                },
                invocation -> payload(),
                EmbeddingPort.unavailable());
    }

    static AutomationExecutionSnapshot executionSnapshot(AgentProfileRef profile) {
        PermissionProfile permission = permissions();
        return new AutomationExecutionSnapshot(
                profile,
                new ProviderRef("provider", 1, "model"),
                new PermissionProfileRef(permission.id(), permission.version()),
                budget(),
                new ToolCatalogSnapshot(
                        TurnId.random(), 1, List.of(tool("com.javaclaw.test", "read", 1)), permission, NOW),
                Optional.empty());
    }

    private static final class EmptyManagedStore implements ManagedExtensionStore {
        @Override
        public <T> T inTransaction(ExtensionId extensionId, TransactionWork<T> work) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtensionResponse inCommand(
                ExtensionId extensionId,
                String operation,
                String idempotencyKey,
                String requestDigest,
                TransactionWork<ExtensionResponse> work) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ExtensionResponse> recoverCommand(
                ExtensionId extensionId, String operation, String idempotencyKey, String requestDigest) {
            return Optional.empty();
        }
    }

    private static final class EmptyInputPort implements InputRequestPort {
        @Override
        public InputRequestRecord open(InputRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<InputRequestRecord> find(String requestId) {
            return Optional.empty();
        }

        @Override
        public InputRequestRecord completeResolved(String requestId, String producerId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class EmptyJobPort implements ExtensionJobPort {
        @Override
        public ExtensionJob submit(
                CanonicalPayload requestIdentity,
                ExtensionJobMutation mutation,
                ExtensionJobSubmissionFactory submissionFactory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ExtensionJob> find(String jobId) {
            return Optional.empty();
        }

        @Override
        public List<ExtensionJob> list(
                Optional<WorkspaceId> workspaceId,
                Optional<ExtensionId> extensionId,
                Set<ExecutionState> states,
                int limit) {
            return List.of();
        }

        @Override
        public ExtensionJobPage page(
                Optional<WorkspaceId> workspaceId,
                Optional<ExtensionId> extensionId,
                Set<ExecutionState> states,
                Optional<ExtensionJobCursor> after,
                int limit) {
            return new ExtensionJobPage(List.of(), Optional.empty());
        }

        @Override
        public ExtensionJob pause(String jobId, ExtensionJobMutation mutation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtensionJob resume(String jobId, ExtensionJobMutation mutation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtensionJob continueWaiting(
                String jobId, ExecutionState waitingState, CanonicalPayload checkpoint, ExtensionJobMutation mutation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtensionJob cancel(String jobId, ExtensionJobMutation mutation) {
            throw new UnsupportedOperationException();
        }
    }
}
