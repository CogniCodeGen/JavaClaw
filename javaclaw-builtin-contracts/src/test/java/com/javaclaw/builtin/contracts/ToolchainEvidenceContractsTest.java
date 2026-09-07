package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstallationState;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstalledToolchain;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolchainEvidenceContractsTest {
    private static final ToolchainRef REF = new ToolchainRef(ToolchainKind.JDK, "25.0.1+8", "a".repeat(64));

    @Test
    void publishedMetadataCannotSmuggleCredentialsFragmentsOrEscapingExecutables() {
        var executables = Map.of("java", "bin/java");
        assertEquals(
                executables,
                artifact("https://downloads.example.test/jdk", "tar.gz", executables, 1)
                        .executablePaths());
        for (String uri : List.of(
                "http://downloads.example.test/a",
                "https:/relative",
                "https://user:password@example.test/a",
                "https://example.test/a#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> artifact(uri, "tar.gz", executables, 1));
        }
        assertThrows(
                IllegalArgumentException.class, () -> artifact("https://example.test/a", "unknown", executables, 1));
        assertThrows(IllegalArgumentException.class, () -> artifact("https://example.test/a", "zip", Map.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> artifact("https://example.test/a", "zip", executables, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> artifact("https://example.test/a", "zip", Map.of("java", "../java"), 1));
    }

    @Test
    void installationAcceptedAndFailedRecordsCannotBeDisplayedAsReady() {
        var installing = new InstalledToolchain(
                REF, "installation", InstallationState.INSTALLING, Optional.empty(), Optional.empty());
        var failed = new InstalledToolchain(
                REF, "installation", InstallationState.FAILED, Optional.empty(), Optional.of("DIGEST_MISMATCH"));
        var ready = new InstalledToolchain(
                REF, "installation", InstallationState.READY, Optional.of(Instant.EPOCH), Optional.empty());
        assertEquals(
                3,
                new CodingEnvironmentContracts.InstalledList(List.of(installing, failed, ready))
                        .toolchains()
                        .size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InstalledToolchain(
                        REF, "installation", InstallationState.READY, Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InstalledToolchain(
                        REF,
                        "installation",
                        InstallationState.INSTALLING,
                        Optional.of(Instant.EPOCH),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InstalledToolchain(
                        REF, "installation", InstallationState.FAILED, Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InstalledToolchain(
                        REF,
                        "installation",
                        InstallationState.READY,
                        Optional.of(Instant.EPOCH),
                        Optional.of("FAILED")));
    }

    private static ToolchainArtifact artifact(String uri, String format, Map<String, String> executables, long bytes) {
        return new ToolchainArtifact(REF, "linux", "arm64", URI.create(uri), format, executables, bytes, "GPL-2.0");
    }
}
