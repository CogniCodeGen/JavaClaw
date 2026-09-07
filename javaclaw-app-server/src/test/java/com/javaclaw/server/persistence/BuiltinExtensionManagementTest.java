package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAccessDeniedException;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.mcp.McpBuiltinExtensionDescriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuiltinExtensionManagementTest {
    private static final Instant NOW = Instant.parse("2026-09-01T02:00:00Z");

    @TempDir
    java.nio.file.Path temporaryDirectory;

    private ExtensionCatalogRepository catalog;

    @BeforeEach
    void initialize() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        catalog = new ExtensionCatalogRepository(database, new CanonicalJson(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 可选扩展启停使用独立状态Revision并持久幂等结果() {
        ExtensionDescriptor descriptor = McpBuiltinExtensionDescriptor.create();
        catalog.installBuiltIn(descriptor);

        var disabled = catalog.disable(
                identity("extension/builtin/disable", "disable-mcp", 1),
                descriptor.id().value());
        var replay = catalog.disable(
                identity("extension/builtin/disable", "disable-mcp", 1),
                descriptor.id().value());

        assertEquals(ExtensionState.DISABLED, disabled.state());
        assertEquals(2, disabled.stateRevision());
        assertEquals(disabled, replay);
        assertEquals(1, disabled.descriptorRevision());
        assertThrows(
                ExtensionAccessDeniedException.class,
                () -> catalog.requireEnabled(descriptor.id(), descriptor.revision()));
        assertEquals(ExtensionState.DISABLED, catalog.state(descriptor.id()));

        var enabled = catalog.enable(
                identity("extension/builtin/enable", "enable-mcp", 2),
                descriptor.id().value());
        assertEquals(ExtensionState.ENABLED, enabled.state());
        assertEquals(3, enabled.stateRevision());
    }

    @Test
    void staleRevision与必需扩展停用均失败关闭() {
        ExtensionDescriptor optional = McpBuiltinExtensionDescriptor.create();
        ExtensionDescriptor required = requiredDescriptor();
        catalog.installBuiltIn(optional);
        catalog.installBuiltIn(required);

        PersistenceException stale = assertThrows(
                PersistenceException.class,
                () -> catalog.disable(
                        identity("extension/builtin/disable", "stale", 2),
                        optional.id().value()));
        PersistenceException immutable = assertThrows(
                PersistenceException.class,
                () -> catalog.disable(
                        identity("extension/builtin/disable", "required", 1),
                        required.id().value()));

        assertEquals(PersistenceException.Kind.REVISION_CONFLICT, stale.kind());
        assertEquals(PersistenceException.Kind.INVALID_REQUEST, immutable.kind());
        assertEquals(2, catalog.listBuiltIns().size());
    }

    private static CommandIdentity identity(String method, String key, long revision) {
        return new CommandIdentity(method, key, revision, "a".repeat(64));
    }

    private static ExtensionDescriptor requiredDescriptor() {
        ExtensionDescriptor mcp = McpBuiltinExtensionDescriptor.create();
        return new ExtensionDescriptor(
                new ExtensionId("com.javaclaw.required"),
                "Required",
                "5.0.0",
                1,
                Set.of(ContributionKind.QUERY),
                new ExtensionRequirements(
                        ExtensionTrust.BUILT_IN,
                        ExtensionAvailability.REQUIRED,
                        2,
                        mcp.requirements().permissionCeiling()));
    }
}
