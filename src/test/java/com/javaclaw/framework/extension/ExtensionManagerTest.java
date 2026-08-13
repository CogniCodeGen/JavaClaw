package com.javaclaw.framework.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.spi.AgentFrameworkExtension;
import com.javaclaw.framework.spi.CapabilityDescriptor;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.ExtensionDescriptor;
import com.javaclaw.framework.spi.ExtensionRegistrar;
import com.javaclaw.framework.spi.ExtensionScope;
import com.javaclaw.framework.spi.ExtensionLock;
import com.javaclaw.framework.spi.HotUpdateCompatibility;
import com.javaclaw.framework.spi.SemanticVersion;
import com.javaclaw.framework.spi.StateMigrator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionManagerTest {

    @Test
    void inFlightGenerationKeepsItsExactBehaviorUntilLeaseCloses() {
        TrackingExtension v1 = extension("1.0.0", 1, false,
                HotUpdateCompatibility.PLAN_ISOLATED);
        TrackingExtension v2 = extension("2.0.0", 1, false,
                HotUpdateCompatibility.PLAN_ISOLATED);
        ExtensionArtifact artifact1 = artifact(v1, '1');
        ExtensionArtifact artifact2 = artifact(v2, '2');

        try (ExtensionManager manager = manager()) {
            manager.publish(List.of(artifact1));
            ExtensionRegistrySnapshot.SnapshotLease runA = manager.acquireCurrent();
            ExtensionLock lock1 = runA.snapshot().resolve("test.extension", "=1.0.0").orElseThrow();

            manager.publish(List.of(artifact2));
            assertEquals(0, v1.stops.get(), "old generation is still leased by Run A");
            assertEquals("1.0.0", guardMarker(runA.snapshot().contributionsFor(List.of(lock1))));
            assertEquals("2.0.0", guardMarker(manager.currentSnapshot().contributions()));

            runA.close();
            assertEquals(1, v1.stops.get(), "old generation retires only after Run A is done");
        }
        assertEquals(1, v2.stops.get());
    }

    @Test
    void restartSnapshotCanCarryCachedOldVersionWithoutActivatingItsContributions() {
        TrackingExtension v1 = extension("1.0.0", 1, false,
                HotUpdateCompatibility.PLAN_ISOLATED);
        TrackingExtension v2 = extension("2.0.0", 1, false,
                HotUpdateCompatibility.PLAN_ISOLATED);
        ExtensionArtifact artifact1 = artifact(v1, 'a');
        ExtensionArtifact artifact2 = artifact(v2, 'b');

        try (ExtensionManager manager = manager()) {
            ExtensionRegistrySnapshot snapshot = manager.publish(List.of(artifact1, artifact2));
            assertEquals("2.0.0", guardMarker(snapshot.contributions()),
                    "new runs see only the newest compatible contribution");
            ExtensionLock oldLock = snapshot.resolve("test.extension", "=1.0.0").orElseThrow();
            try (ExtensionRegistrySnapshot.SnapshotLease restored = manager
                    .acquireLocked(List.of(oldLock)).orElseThrow()) {
                assertEquals("1.0.0",
                        guardMarker(restored.snapshot().contributionsFor(List.of(oldLock))));
            }
        }
        assertEquals(1, v1.starts.get());
        assertEquals(1, v2.starts.get());
        assertEquals(1, v1.stops.get());
        assertEquals(1, v2.stops.get());
    }

    @Test
    void coordinateContentCannotChangeWithoutVersionBump() {
        TrackingExtension extension = extension("1.0.0", 1, false,
                HotUpdateCompatibility.PLAN_ISOLATED);
        try (ExtensionManager manager = manager()) {
            manager.publish(List.of(artifact(extension, '1')));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> manager.publish(List.of(artifact(extension, '2'))));
            assertTrue(failure.getMessage().contains("version bump"));
        }
    }

    @Test
    void disablingBlocksNewResolutionButKeepsLockedVersionRecoverable() {
        TrackingExtension extension = extension("1.0.0", 1, false,
                HotUpdateCompatibility.PLAN_ISOLATED);
        try (ExtensionManager manager = manager()) {
            ExtensionRegistrySnapshot installed = manager.publish(
                    List.of(artifact(extension, 'd')));
            ExtensionLock lock = installed.resolve("test.extension", "1.0.0").orElseThrow();

            ExtensionRegistrySnapshot disabled = manager.disableForNewRuns("test.extension");
            assertTrue(disabled.resolve("test.extension", "*").isEmpty());
            assertTrue(disabled.contributions().capabilities().isEmpty());
            try (ExtensionRegistrySnapshot.SnapshotLease restored = manager
                    .acquireLocked(List.of(lock)).orElseThrow()) {
                assertEquals("1.0.0",
                        guardMarker(restored.snapshot().contributionsFor(List.of(lock))));
            }
        }
    }

    @Test
    void sharedStateUpgradeRequiresACompleteMigrationPath() {
        TrackingExtension v1 = extension("1.0.0", 1, false,
                HotUpdateCompatibility.HOT_COMPATIBLE);
        TrackingExtension invalidV2 = extension("2.0.0", 2, false,
                HotUpdateCompatibility.HOT_COMPATIBLE);
        try (ExtensionManager manager = manager()) {
            manager.publish(List.of(artifact(v1, '1')));
            assertThrows(IllegalStateException.class,
                    () -> manager.publish(List.of(artifact(invalidV2, '2'))));

            TrackingExtension validV2 = extension("2.0.0", 2, true,
                    HotUpdateCompatibility.HOT_COMPATIBLE);
            assertNotNull(manager.publish(List.of(artifact(validV2, '3'))));
        }
    }

    private static ExtensionManager manager() {
        return new ExtensionManager(new ExtensionContext(
                Clock.systemUTC(), Runnable::run,
                request -> CompletableFuture.failedFuture(
                        new AssertionError("model task not expected"))));
    }

    private static TrackingExtension extension(
            String version, int schemaVersion, boolean migrates,
            HotUpdateCompatibility compatibility) {
        return new TrackingExtension(version, schemaVersion, migrates, compatibility);
    }

    private static ExtensionArtifact artifact(TrackingExtension extension, char hashDigit) {
        return new ExtensionArtifact(extension, String.valueOf(hashDigit).repeat(64), null);
    }

    private static String guardMarker(ExtensionContributions contributions) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        JsonNode output = contributions.outputGuards().getFirst().value()
                .validate(input, null, null);
        return output.path("extensionVersion").asText();
    }

    private static final class TrackingExtension implements AgentFrameworkExtension {
        private final ExtensionDescriptor descriptor;
        private final boolean migrates;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();

        private TrackingExtension(
                String version, int schemaVersion, boolean migrates,
                HotUpdateCompatibility compatibility) {
            this.descriptor = new ExtensionDescriptor(
                    "test.extension", SemanticVersion.parse(version), ">=2.0.0 <3.0.0",
                    ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                    compatibility, schemaVersion, Map.of());
            this.migrates = migrates;
        }

        @Override
        public ExtensionDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void register(ExtensionRegistrar registrar) {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("type", "object");
            registrar.capability(new CapabilityDescriptor(
                    new CapabilityId("test.extension"), "Test", "", schema,
                    JsonNodeFactory.instance.objectNode()),
                    (id, configuration, context) -> configuration.deepCopy());
            registrar.outputGuard((output, request, runId) -> {
                ObjectNode result = output.deepCopy();
                result.put("extensionVersion", descriptor.version().toString());
                return result;
            });
            if (migrates) {
                registrar.stateMigrator(new StateMigrator() {
                    @Override public String extensionId() { return descriptor.id(); }
                    @Override public int fromVersion() { return 1; }
                    @Override public int toVersion() { return 2; }
                    @Override public JsonNode migrate(JsonNode state) { return state.deepCopy(); }
                });
            }
        }

        @Override
        public void start(ExtensionContext context) {
            starts.incrementAndGet();
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
        }
    }
}
