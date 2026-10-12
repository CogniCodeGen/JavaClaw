package com.javaclaw.framework.store;

import com.javaclaw.framework.spi.ExtensionArtifactRecord;
import com.javaclaw.framework.spi.SemanticVersion;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExtensionArtifactRepositoryTest {
    @TempDir Path temporary;

    @Test
    void authorizationIsHashBoundWhileDisableRetainsRecoveryArtifact() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:extensions-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcExtensionArtifactRepository repository = new JdbcExtensionArtifactRepository(
                jdbc, new DataSourceTransactionManager(dataSource), Clock.systemUTC());
        SemanticVersion version = SemanticVersion.parse("1.2.3");
        Path jar = temporary.resolve("extension.jar");
        ExtensionArtifactRecord artifact = new ExtensionArtifactRecord(
                "test.extension", version, "a".repeat(64), jar,
                true, true, Instant.now());

        repository.authorize(artifact);
        assertEquals(1, repository.authorizedArtifacts().size());
        assertTrue(repository.authorizedArtifacts().getFirst().enabledForNewRuns());
        assertThrows(IllegalStateException.class, () -> repository.authorize(
                new ExtensionArtifactRecord("test.extension", version, "b".repeat(64), jar,
                        true, true, Instant.now())));

        assertEquals(1, repository.setEnabledForNewRuns("test.extension", false));
        ExtensionArtifactRecord disabled = repository.authorizedArtifacts().getFirst();
        assertFalse(disabled.enabledForNewRuns());
        assertTrue(disabled.authorized(), "disabled JAR remains authorized for locked-run recovery");
        assertTrue(repository.revoke("test.extension", version, "a".repeat(64)));
        assertTrue(repository.authorizedArtifacts().isEmpty());
    }

    @Test
    void multiExtensionAuthorizationRollsBackWhenAnyCoordinateConflicts() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:extensions-batch-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcExtensionArtifactRepository repository = new JdbcExtensionArtifactRepository(
                new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource),
                Clock.systemUTC());
        SemanticVersion version = SemanticVersion.parse("1.0.0");
        repository.authorize(record("existing", version, "a"));

        assertThrows(IllegalStateException.class, () -> repository.authorizeAll(List.of(
                record("new-extension", version, "b"),
                record("existing", version, "c"))));

        List<ExtensionArtifactRecord> authorized = repository.authorizedArtifacts();
        assertEquals(1, authorized.size());
        assertEquals("existing", authorized.getFirst().extensionId());
    }

    @Test
    void duplicateAuthorizationDoesNotOverwriteOrRevokeTheExistingRecord() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:extensions-duplicate-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcExtensionArtifactRepository repository = new JdbcExtensionArtifactRepository(
                new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource),
                Clock.systemUTC());
        SemanticVersion version = SemanticVersion.parse("1.0.0");
        ExtensionArtifactRecord original = record("existing", version, "a");
        repository.authorize(original);
        repository.setEnabledForNewRuns("existing", false);
        ExtensionArtifactRecord before = repository.authorizedArtifacts().getFirst();

        assertThrows(IllegalStateException.class, () -> repository.authorize(
                new ExtensionArtifactRecord("existing", version, "a".repeat(64),
                        temporary.resolve("replacement.jar"), true, true,
                        Instant.now().plusSeconds(60))));

        ExtensionArtifactRecord after = repository.authorizedArtifacts().getFirst();
        assertEquals(before.artifactPath(), after.artifactPath());
        assertEquals(before.cachedAt(), after.cachedAt());
        assertFalse(after.enabledForNewRuns());
        assertTrue(after.authorized());
    }

    @Test
    void duplicateInMixedJarLeavesNewCoordinatesUnauthorized() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:extensions-mixed-duplicate-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcExtensionArtifactRepository repository = new JdbcExtensionArtifactRepository(
                new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource),
                Clock.systemUTC());
        SemanticVersion version = SemanticVersion.parse("1.0.0");
        repository.authorize(record("existing", version, "a"));

        assertThrows(IllegalStateException.class, () -> repository.authorizeAll(List.of(
                record("new-extension", version, "b"),
                record("existing", version, "a"))));

        assertEquals(List.of("existing"), repository.authorizedArtifacts().stream()
                .map(ExtensionArtifactRecord::extensionId).toList());
    }

    @Test
    void revokedArtifactCanBeAuthorizedAgain() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:extensions-reauthorize-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcExtensionArtifactRepository repository = new JdbcExtensionArtifactRepository(
                new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource),
                Clock.systemUTC());
        SemanticVersion version = SemanticVersion.parse("1.0.0");
        ExtensionArtifactRecord artifact = record("reinstallable", version, "a");
        repository.authorize(artifact);
        assertTrue(repository.revoke(
                artifact.extensionId(), artifact.version(), artifact.artifactSha256()));

        repository.authorize(artifact);

        assertEquals(1, repository.authorizedArtifacts().size());
        assertEquals("reinstallable",
                repository.authorizedArtifacts().getFirst().extensionId());
    }

    private ExtensionArtifactRecord record(
            String id, SemanticVersion version, String hashCharacter) {
        return new ExtensionArtifactRecord(id, version, hashCharacter.repeat(64),
                temporary.resolve(id + ".jar"), true, true, Instant.now());
    }
}
