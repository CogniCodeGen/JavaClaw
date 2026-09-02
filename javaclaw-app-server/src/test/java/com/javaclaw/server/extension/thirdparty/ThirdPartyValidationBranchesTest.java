package com.javaclaw.server.extension.thirdparty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.ExtensionTrustKeyRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyValidationBranchesTest {
    @TempDir
    Path temporaryDirectory;

    private final CanonicalJson json = new CanonicalJson();

    @Test
    void trustCatalogRejectsRevokedChangedAndMalformedKeyIdentities() {
        KeyPair keys = ThirdPartyBundleTestFixtures.keyPair();
        byte[] encoded = keys.getPublic().getEncoded();
        ExtensionTrustKeyRecord active = ExtensionTrustKeyRecord.active(
                ThirdPartyBundleTestFixtures.KEY_ID,
                ThirdPartyBundleTestFixtures.digest(encoded),
                "a".repeat(64),
                encoded,
                java.time.Instant.parse("2026-09-02T00:00:00Z"));
        BundleRpcContracts.TrustKey revokedMetadata = new BundleRpcContracts.TrustKey(
                active.metadata().id(),
                active.metadata().fingerprint(),
                active.metadata().attachmentDigest(),
                2,
                BundleRpcContracts.TrustState.REVOKED,
                active.metadata().createdAt(),
                active.metadata().updatedAt());
        ExtensionTrustKeyRecord revoked = new ExtensionTrustKeyRecord(revokedMetadata, encoded);
        ExtensionTrustedKeys trusted = ExtensionTrustedKeys.from(List.of(revoked, active));

        assertEquals(
                active.metadata().fingerprint(),
                trusted.fingerprint(active.metadata().id()));
        assertThrows(IllegalArgumentException.class, () -> trusted.trust(revoked));
        ExtensionTrustKeyRecord changedFingerprint = ExtensionTrustKeyRecord.active(
                "changed-fingerprint",
                "f".repeat(64),
                "b".repeat(64),
                encoded,
                active.metadata().createdAt());
        assertThrows(SecurityException.class, () -> trusted.trust(changedFingerprint));
        assertThrows(
                SecurityException.class,
                () -> trusted.verify(
                        active.metadata().id(), "manifest".getBytes(StandardCharsets.UTF_8), new byte[64]));
        assertThrows(SecurityException.class, () -> trusted.fingerprint("unknown-key"));
        assertThrows(IllegalArgumentException.class, () -> trusted.revoke(null));
        assertThrows(IllegalArgumentException.class, () -> trusted.revoke("bad/key"));
    }

    @Test
    void manifestRejectsAmbiguousNamesFilesOperationsAndDuplicateIdentifiers() {
        ThirdPartyBundleManifest valid = ThirdPartyBundleTestFixtures.manifest(json);

        assertThrows(
                IllegalArgumentException.class,
                () -> copy(
                        valid,
                        " ",
                        valid.entryPoint(),
                        valid.permissions(),
                        valid.contributions(),
                        valid.schemas(),
                        valid.files()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest(
                        valid.formatVersion(),
                        valid.id(),
                        valid.displayName(),
                        valid.version(),
                        "bad/key",
                        valid.entryPoint(),
                        valid.permissions(),
                        valid.contributions(),
                        valid.schemas(),
                        valid.files()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(
                        valid,
                        valid.displayName(),
                        valid.entryPoint(),
                        valid.permissions(),
                        valid.contributions(),
                        valid.schemas(),
                        List.of(new ThirdPartyBundleManifest.BundleFile("other", "a".repeat(64), 0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest.EntryPoint("bin/worker", List.of("bad\0argument")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest.Contribution(
                        "query", ContributionKind.QUERY, Set.of(" "), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest.Contribution(
                        "query", ContributionKind.QUERY, Set.of("status"), Optional.of(json.parse("{}"))));

        ThirdPartyBundleManifest.Contribution orchestrator = new ThirdPartyBundleManifest.Contribution(
                "orchestrator", ContributionKind.ORCHESTRATOR, Set.of("start"), Optional.empty());
        assertEquals(ContributionKind.ORCHESTRATOR, orchestrator.kind());
    }

    @Test
    void manifestRejectsDuplicateContributionIdentifiers() {
        ThirdPartyBundleManifest valid = ThirdPartyBundleTestFixtures.manifest(json);
        List<ThirdPartyBundleManifest.Contribution> duplicated = new ArrayList<>(valid.contributions());
        duplicated.add(valid.contributions().getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(
                        valid,
                        valid.displayName(),
                        valid.entryPoint(),
                        valid.permissions(),
                        duplicated,
                        valid.schemas(),
                        valid.files()));
    }

    @Test
    void permissionCeilingChecksEveryIndependentResourceAndWorkspaceBranch() {
        ThirdPartyBundleManifest valid = ThirdPartyBundleTestFixtures.manifest(json);
        assertInvalidPermission(
                valid, permission(valid, Duration.ZERO, valid.permissions().resources(), true, false));
        assertInvalidPermission(
                valid,
                permission(valid, Duration.ofSeconds(-1), valid.permissions().resources(), true, false));
        assertInvalidPermission(
                valid,
                permission(valid, Duration.ofSeconds(1), new ResourceLimits(1, 17L * 1024 * 1024, 1, 1), true, false));
        assertInvalidPermission(
                valid, permission(valid, Duration.ofSeconds(1), new ResourceLimits(1, 1, 5, 1), true, false));
        assertInvalidPermission(
                valid, permission(valid, Duration.ofSeconds(1), new ResourceLimits(1, 1, 1, 65), true, false));

        ThirdPartyBundleManifest noWorkspace = withPermission(
                valid, permission(valid, Duration.ofSeconds(1), new ResourceLimits(1, 1, 1, 1), false, false));
        var noWorkspacePermission = ThirdPartyPermissionPolicy.processPermission(
                noWorkspace,
                temporaryDirectory,
                Optional.of(temporaryDirectory.resolve("workspace")),
                Optional.empty(),
                json);
        assertTrue(noWorkspacePermission.files().writeRoots().isEmpty());
        assertEquals(
                List.of(temporaryDirectory.toAbsolutePath().normalize()),
                noWorkspacePermission.files().readRoots());

        ThirdPartyBundleManifest writable = withPermission(
                valid, permission(valid, Duration.ofSeconds(1), new ResourceLimits(1, 1, 1, 1), true, true));
        var writablePermission = ThirdPartyPermissionPolicy.processPermission(
                writable,
                temporaryDirectory,
                Optional.of(temporaryDirectory.resolve("workspace")),
                Optional.empty(),
                json);
        assertFalse(writablePermission.files().writeRoots().isEmpty());
    }

    private void assertInvalidPermission(
            ThirdPartyBundleManifest source, ThirdPartyBundleManifest.PermissionRequest permission) {
        ThirdPartyBundleManifest manifest = withPermission(source, permission);
        assertThrows(IllegalArgumentException.class, () -> ThirdPartyPermissionPolicy.validate(manifest, json));
    }

    private static ThirdPartyBundleManifest.PermissionRequest permission(
            ThirdPartyBundleManifest source,
            Duration duration,
            ResourceLimits resources,
            boolean workspaceRead,
            boolean workspaceWrite) {
        return new ThirdPartyBundleManifest.PermissionRequest(
                workspaceRead,
                workspaceWrite,
                false,
                source.permissions().networkHosts(),
                source.permissions().networkPorts(),
                source.permissions().tlsOnly(),
                duration,
                resources,
                ToolRisk.READ_ONLY);
    }

    private static ThirdPartyBundleManifest withPermission(
            ThirdPartyBundleManifest source, ThirdPartyBundleManifest.PermissionRequest permission) {
        return copy(
                source,
                source.displayName(),
                source.entryPoint(),
                permission,
                source.contributions(),
                source.schemas(),
                source.files());
    }

    private static ThirdPartyBundleManifest copy(
            ThirdPartyBundleManifest source,
            String displayName,
            ThirdPartyBundleManifest.EntryPoint entryPoint,
            ThirdPartyBundleManifest.PermissionRequest permission,
            List<ThirdPartyBundleManifest.Contribution> contributions,
            List<ThirdPartyBundleManifest.SchemaDocument> schemas,
            List<ThirdPartyBundleManifest.BundleFile> files) {
        return new ThirdPartyBundleManifest(
                source.formatVersion(),
                source.id(),
                displayName,
                source.version(),
                source.signingKeyId(),
                entryPoint,
                permission,
                contributions,
                schemas,
                files);
    }
}
