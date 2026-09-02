package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionContractsTest {
    @Test
    void identifiersDescriptorsAndRequirementsNormalizeAndValidate() {
        ExtensionId first = new ExtensionId(" com.javaclaw.alpha ");
        ExtensionId second = new ExtensionId("com.javaclaw.beta");
        ExtensionDescriptor descriptor = SpiFixtures.descriptor();

        assertEquals("com.javaclaw.alpha", first.value());
        assertTrue(first.compareTo(second) < 0);
        assertEquals(2, descriptor.requirements().minimumProtocolVersion());
        assertThrows(IllegalArgumentException.class, () -> new ExtensionId("Bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionRequirements(
                        ExtensionTrust.BUILT_IN, ExtensionAvailability.OPTIONAL, 1, SpiFixtures.permissions()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionDescriptor(
                        first, " ", "1", 1, Set.of(ContributionKind.QUERY), descriptor.requirements()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionDescriptor(
                        first, "Alpha", "1", 0, Set.of(ContributionKind.QUERY), descriptor.requirements()));
    }

    @Test
    void requestResponseSchemaAndDocumentEnforceTextAndRevisionContracts() {
        ExtensionRequest request = new ExtensionRequest(
                WorkspaceId.random(),
                Optional.of(ThreadId.random()),
                Optional.of(TurnId.random()),
                " read ",
                SpiFixtures.payload(),
                Optional.of(" key "),
                0,
                Optional.empty());

        assertEquals("read", request.operation());
        assertEquals(Optional.of("key"), request.idempotencyKey());
        assertEquals(3, new ExtensionResponse(SpiFixtures.payload(), 3).revision());
        assertEquals("schema.test", new ExtensionSchema(" schema.test ", SpiFixtures.payload()).schemaId());
        assertEquals(1, new VersionedDocument(" key ", 1, SpiFixtures.payload(), SpiFixtures.NOW).revision());
        assertThrows(IllegalArgumentException.class, () -> requestWith(" ", 0));
        assertThrows(IllegalArgumentException.class, () -> requestWith("read", -1));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionResponse(SpiFixtures.payload(), -1));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionSchema(" ", SpiFixtures.payload()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VersionedDocument("key", 0, SpiFixtures.payload(), SpiFixtures.NOW));
    }

    @Test
    void orchestrationContractsAcceptTerminalResultsAndRejectInvalidBounds() {
        OrchestratedTurnCommand command = command("Title", "model", "instruction", "step-1");
        OrchestratedTurnResult result = new OrchestratedTurnResult(
                ThreadId.random(), TurnId.random(), TurnStatus.COMPLETED, SpiFixtures.payload());
        OrchestratedTurnSummary summary = new OrchestratedTurnSummary("done", 1, 2, 3, Optional.of(" "), List.of());

        assertEquals("Title", command.title());
        assertEquals(TurnStatus.COMPLETED, result.status());
        assertTrue(summary.errorCode().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> command(" ", "model", "instruction", "key"));
        assertThrows(IllegalArgumentException.class, () -> command("Title", "m".repeat(241), "instruction", "key"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OrchestratedTurnResult(
                        ThreadId.random(), TurnId.random(), TurnStatus.RUNNING, SpiFixtures.payload()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OrchestratedTurnSummary("", -1, 0, 0, Optional.empty(), List.of()));
    }

    @Test
    void bundleDefaultsExposeNoSchemaAndNoRestoreWork() throws Exception {
        ExtensionBundle bundle = new ExtensionBundle() {
            @Override
            public ExtensionDescriptor descriptor() {
                return SpiFixtures.descriptor();
            }

            @Override
            public List<ExtensionContribution> start(ExtensionContext context) {
                return List.of();
            }

            @Override
            public void close() {}
        };

        assertTrue(bundle.schemas().isEmpty());
        bundle.restore(SpiFixtures.executionContext());
        assertEquals(SpiFixtures.descriptor(), bundle.descriptor());
    }

    private static ExtensionRequest requestWith(String operation, long revision) {
        return new ExtensionRequest(
                WorkspaceId.random(),
                Optional.empty(),
                Optional.empty(),
                operation,
                SpiFixtures.payload(),
                Optional.empty(),
                revision,
                Optional.empty());
    }

    private static OrchestratedTurnCommand command(String title, String model, String instruction, String key) {
        return new OrchestratedTurnCommand(
                WorkspaceId.random(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                title,
                SpiFixtures.executionSnapshot(new AgentProfileRef(model, 1)),
                instruction,
                SpiFixtures.payload(),
                key);
    }
}
