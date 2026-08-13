package com.javaclaw.framework.store;

import com.javaclaw.framework.spi.ExtensionArtifactRecord;
import com.javaclaw.framework.spi.ExtensionArtifactRepository;
import com.javaclaw.framework.spi.SemanticVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** H2 authorization index for immutable system-extension artifacts. */
public final class JdbcExtensionArtifactRepository implements ExtensionArtifactRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcExtensionArtifactRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void authorize(ExtensionArtifactRecord artifact) {
        authorizeAll(List.of(artifact));
    }

    @Override
    public synchronized void authorizeAll(List<ExtensionArtifactRecord> artifacts) {
        List<ExtensionArtifactRecord> checked = List.copyOf(
                Objects.requireNonNull(artifacts, "artifacts"));
        if (checked.isEmpty()) throw new IllegalArgumentException("artifacts must not be empty");
        transactions.executeWithoutResult(status -> {
            java.util.LinkedHashMap<String, String> batchCoordinates = new java.util.LinkedHashMap<>();
            java.util.HashSet<String> reauthorizations = new java.util.HashSet<>();
            for (ExtensionArtifactRecord artifact : checked) {
                String coordinate = artifact.extensionId() + ":" + artifact.version();
                String batchHash = batchCoordinates.putIfAbsent(
                        coordinate, artifact.artifactSha256());
                if (batchHash != null) {
                    if (!batchHash.equals(artifact.artifactSha256())) {
                        throw new IllegalStateException(
                                "extension coordinate appears with multiple artifacts: " + coordinate);
                    }
                    throw new IllegalStateException(
                            "extension coordinate appears more than once: " + coordinate);
                }
                List<KnownArtifact> known = jdbc.query("""
                        SELECT artifact_sha256, authorized FROM extension_artifact_cache
                        WHERE extension_id = ? AND extension_version = ?
                        """, (row, index) -> new KnownArtifact(
                                row.getString("artifact_sha256"),
                                row.getBoolean("authorized")),
                        artifact.extensionId(), artifact.version().toString());
                if (known.stream().anyMatch(value ->
                        !value.sha256().equals(artifact.artifactSha256()))) {
                    throw new IllegalStateException("extension coordinate already has another artifact; "
                            + "bump the version: " + coordinate);
                }
                KnownArtifact same = known.stream().findFirst().orElse(null);
                if (same != null && same.authorized()) {
                    throw new IllegalStateException(
                            "extension coordinate is already authorized: " + coordinate);
                }
                if (same != null) reauthorizations.add(coordinate);
            }
            for (ExtensionArtifactRecord artifact : checked) {
                String coordinate = artifact.extensionId() + ":" + artifact.version();
                if (reauthorizations.contains(coordinate)) {
                    int updated = jdbc.update("""
                            UPDATE extension_artifact_cache
                            SET artifact_path = ?, authorized = TRUE,
                                enabled_for_new_runs = TRUE, cached_at = ?
                            WHERE extension_id = ? AND extension_version = ?
                              AND artifact_sha256 = ? AND authorized = FALSE
                            """, artifact.artifactPath().toString(), clock.millis(),
                            artifact.extensionId(), artifact.version().toString(),
                            artifact.artifactSha256());
                    if (updated != 1) {
                        throw new IllegalStateException(
                                "extension authorization changed concurrently: " + coordinate);
                    }
                } else {
                    jdbc.update("""
                        INSERT INTO extension_artifact_cache(
                            extension_id, extension_version, artifact_sha256,
                            artifact_path, authorized, enabled_for_new_runs, cached_at)
                        VALUES (?, ?, ?, ?, TRUE, TRUE, ?)
                        """, artifact.extensionId(), artifact.version().toString(),
                        artifact.artifactSha256(), artifact.artifactPath().toString(), clock.millis());
                }
            }
        });
    }

    @Override
    public List<ExtensionArtifactRecord> authorizedArtifacts() {
        return jdbc.query("""
                SELECT extension_id, extension_version, artifact_sha256,
                       artifact_path, authorized, enabled_for_new_runs, cached_at
                FROM extension_artifact_cache WHERE authorized = TRUE
                ORDER BY extension_id, extension_version, artifact_sha256
                """, (row, index) -> new ExtensionArtifactRecord(
                row.getString("extension_id"),
                SemanticVersion.parse(row.getString("extension_version")),
                row.getString("artifact_sha256"), Path.of(row.getString("artifact_path")),
                row.getBoolean("authorized"),
                row.getBoolean("enabled_for_new_runs"),
                Instant.ofEpochMilli(row.getLong("cached_at"))));
    }

    @Override
    public int setEnabledForNewRuns(String extensionId, boolean enabled) {
        return jdbc.update("""
                UPDATE extension_artifact_cache SET enabled_for_new_runs = ?
                WHERE extension_id = ? AND authorized = TRUE
                """, enabled, extensionId);
    }

    @Override
    public boolean revoke(
            String extensionId, SemanticVersion version, String artifactSha256) {
        return jdbc.update("""
                UPDATE extension_artifact_cache SET authorized = FALSE
                WHERE extension_id = ? AND extension_version = ? AND artifact_sha256 = ?
                """, extensionId, version.toString(), artifactSha256) == 1;
    }

    @Override
    public void revokeAll(List<ExtensionArtifactRecord> artifacts) {
        List<ExtensionArtifactRecord> checked = List.copyOf(
                Objects.requireNonNull(artifacts, "artifacts"));
        transactions.executeWithoutResult(status -> checked.forEach(artifact -> jdbc.update("""
                UPDATE extension_artifact_cache SET authorized = FALSE
                WHERE extension_id = ? AND extension_version = ? AND artifact_sha256 = ?
                """, artifact.extensionId(), artifact.version().toString(),
                artifact.artifactSha256())));
    }

    private record KnownArtifact(String sha256, boolean authorized) { }
}
