package com.javaclaw.server.security;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionPresetCatalogTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void reviewPresetIsBoundToWorkspaceAndCannotStartProcesses() {
        PermissionPresetCatalog catalog = new PermissionPresetCatalog();
        Workspace workspace = workspace();
        PermissionPresetInstantiationRequest request = new PermissionPresetInstantiationRequest(
                "workspace-review", 1, workspace.id(), "review-profile", Set.of("memory_search"), Set.of());

        var profile = catalog.preview(request, workspace).proposedProfile();

        assertEquals(
                java.util.List.of(root.toAbsolutePath().normalize()),
                profile.files().readRoots());
        assertTrue(profile.files().writeRoots().isEmpty());
        assertFalse(profile.files().allowDelete());
        assertEquals(ToolRisk.READ_ONLY, profile.tools().maximumRisk());
        assertTrue(profile.processes().executables().isEmpty());
    }

    @Test
    void developerPresetKeepsNetworkAndPtyClosed() {
        PermissionPresetCatalog catalog = new PermissionPresetCatalog();
        Workspace workspace = workspace();
        PermissionPresetInstantiationRequest request = new PermissionPresetInstantiationRequest(
                "workspace-developer", 1, workspace.id(), "developer-profile", Set.of("file_edit"), Set.of("git"));

        var preview = catalog.preview(request, workspace);
        var profile = preview.proposedProfile();

        assertEquals(
                java.util.List.of(root.toAbsolutePath().normalize()),
                profile.files().writeRoots());
        assertTrue(profile.files().allowDelete());
        assertTrue(profile.network().hosts().isEmpty());
        assertFalse(profile.processes().allowPty());
        assertEquals(Set.of("git"), profile.processes().executables());
        assertFalse(preview.warnings().isEmpty());
    }

    @Test
    void reviewPresetRejectsExecutableSelection() {
        PermissionPresetCatalog catalog = new PermissionPresetCatalog();
        Workspace workspace = workspace();
        PermissionPresetInstantiationRequest request = new PermissionPresetInstantiationRequest(
                "workspace-review", 1, workspace.id(), "review-profile", Set.of(), Set.of("git"));

        assertThrows(IllegalArgumentException.class, () -> catalog.preview(request, workspace));
    }

    private Workspace workspace() {
        return new Workspace(WorkspaceId.random(), "Workspace", root, WorkspaceLifecycle.ACTIVE, 1, NOW, NOW);
    }
}
