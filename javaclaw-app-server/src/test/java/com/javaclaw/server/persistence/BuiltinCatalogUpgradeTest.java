package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAccessDeniedException;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.mcp.McpBuiltinExtensionDescriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuiltinCatalogUpgradeTest {
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    @TempDir
    Path temporary;

    private final CanonicalJson json = new CanonicalJson();
    private final ExtensionDescriptor previous = descriptor(1);
    private final ExtensionDescriptor current = descriptor(2);
    private H2Database database;
    private ExtensionCatalogRepository catalog;

    @BeforeEach
    void initialize() {
        database = new H2Database(temporary.resolve("data-v6"));
        database.initialize();
        catalog = catalogAt(NOW);
        catalog.installBuiltIn(previous);
    }

    @Test
    void 只有声明完整前驱的升级可成功且旧冻结Revision立即失效() {
        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(current));

        catalog.installBuiltIn(current, List.of(previous));
        var status = catalog.requireBuiltIn(current.id().value());
        assertEquals(2, status.descriptorRevision());
        assertEquals(1, status.stateRevision());
        assertEquals(ExtensionState.ENABLED, status.state());
        catalog.requireEnabled(current.id(), 2);
        assertThrows(ExtensionAccessDeniedException.class, () -> catalog.requireEnabled(current.id(), 1));

        catalogAt(NOW.plusSeconds(60)).installBuiltIn(current, List.of(previous));
        assertEquals(status, catalog.requireBuiltIn(current.id().value()));
        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(previous));
    }

    @Test
    void 升级保留禁用状态独立状态版本和原命令幂等回执() {
        CommandIdentity identity = new CommandIdentity("extension/builtin/disable", "disabled", 1, "a".repeat(64));
        var disabled = catalog.disable(identity, previous.id().value());

        catalogAt(NOW.plusSeconds(60)).installBuiltIn(current, List.of(previous));
        var upgraded = catalog.requireBuiltIn(current.id().value());
        assertEquals(2, upgraded.descriptorRevision());
        assertEquals(disabled.stateRevision(), upgraded.stateRevision());
        assertEquals(ExtensionState.DISABLED, upgraded.state());
        assertEquals(disabled, catalog.disable(identity, previous.id().value()));
        assertThrows(ExtensionAccessDeniedException.class, () -> catalog.requireEnabled(current.id(), 2));
    }

    @Test
    void 即使旧版本相同未知描述也不能被发行版升级覆盖() throws Exception {
        ExtensionDescriptor unknown = new ExtensionDescriptor(
                previous.id(),
                "未声明的差异",
                previous.version(),
                previous.revision(),
                previous.contributionKinds(),
                previous.requirements());
        replaceDescriptor(json.encode(unknown).json());

        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(current, List.of(previous)));
        assertEquals(1, catalog.requireBuiltIn(previous.id().value()).descriptorRevision());
        assertEquals(json.encode(unknown).json(), storedDescriptor());
    }

    @Test
    void 行信任身份版本与损坏清单均不能通过前驱白名单() throws Exception {
        updateRow("TRUST_LEVEL = 'THIRD_PARTY'");
        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(current, List.of(previous)));
        updateRow("TRUST_LEVEL = 'BUILT_IN', REVISION = 2");
        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(current, List.of(previous)));
        updateRow("REVISION = 1");
        ExtensionDescriptor other = new ExtensionDescriptor(
                new ExtensionId("builtin.other"),
                previous.displayName(),
                previous.version(),
                1,
                previous.contributionKinds(),
                previous.requirements());
        replaceDescriptor(json.encode(other).json());
        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(current, List.of(previous)));
        replaceDescriptor("{invalid}");
        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(current, List.of(previous)));
    }

    @Test
    void 拒绝非单调跨身份与第三方前驱声明且不修改原记录() {
        ExtensionDescriptor other = new ExtensionDescriptor(
                new ExtensionId("builtin.other"),
                previous.displayName(),
                previous.version(),
                1,
                previous.contributionKinds(),
                previous.requirements());
        ExtensionRequirements requirements = previous.requirements();
        ExtensionDescriptor thirdParty = new ExtensionDescriptor(
                previous.id(),
                previous.displayName(),
                previous.version(),
                1,
                previous.contributionKinds(),
                new ExtensionRequirements(
                        ExtensionTrust.THIRD_PARTY,
                        requirements.availability(),
                        requirements.minimumProtocolVersion(),
                        requirements.permissionCeiling()));
        for (ExtensionDescriptor invalid : List.of(current, descriptor(3), other, thirdParty)) {
            assertThrows(IllegalArgumentException.class, () -> catalog.installBuiltIn(current, List.of(invalid)));
        }
        assertEquals(1, catalog.requireBuiltIn(previous.id().value()).descriptorRevision());
    }

    @Test
    void 升级按Set语义匹配并保持时间在系统时钟回拨时不倒退() throws Exception {
        String reordered = json.encode(previous)
                .json()
                .replace(
                        "\"contributionKinds\":[\"COMMAND\",\"QUERY\",\"VIEW\"]",
                        "\"contributionKinds\":[\"VIEW\",\"QUERY\",\"COMMAND\"]");
        replaceDescriptor(reordered);

        catalogAt(NOW.minusSeconds(60)).installBuiltIn(current, List.of(previous));

        assertEquals(NOW, catalog.requireBuiltIn(current.id().value()).updatedAt());
        assertEquals(json.encode(current).json(), storedDescriptor());
    }

    private ExtensionCatalogRepository catalogAt(Instant instant) {
        return new ExtensionCatalogRepository(database, json, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static ExtensionDescriptor descriptor(long revision) {
        ExtensionDescriptor base = McpBuiltinExtensionDescriptor.create();
        return new ExtensionDescriptor(
                new ExtensionId("builtin.upgrade"),
                "迁移测试",
                "6.0." + revision,
                revision,
                Set.of(ContributionKind.QUERY, ContributionKind.COMMAND, ContributionKind.VIEW),
                base.requirements());
    }

    private void replaceDescriptor(String payload) throws Exception {
        new H2Transactions(database).execute(connection -> {
            try (var statement = connection.prepareStatement("UPDATE CORE.EXTENSION SET DESCRIPTOR = ? WHERE ID = ?")) {
                statement.setString(1, payload);
                statement.setString(2, previous.id().value());
                assertEquals(1, statement.executeUpdate());
            }
            return null;
        });
    }

    private void updateRow(String assignments) throws Exception {
        new H2Transactions(database).execute(connection -> {
            try (var statement =
                    connection.prepareStatement("UPDATE CORE.EXTENSION SET " + assignments + " WHERE ID = ?")) {
                statement.setString(1, previous.id().value());
                assertEquals(1, statement.executeUpdate());
            }
            return null;
        });
    }

    private String storedDescriptor() throws Exception {
        return new H2Transactions(database).execute(connection -> {
            try (var statement = connection.prepareStatement("SELECT DESCRIPTOR FROM CORE.EXTENSION WHERE ID = ?")) {
                statement.setString(1, previous.id().value());
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getString(1);
                }
            }
        });
    }
}
