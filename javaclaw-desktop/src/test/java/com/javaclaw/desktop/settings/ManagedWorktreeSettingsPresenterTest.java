package com.javaclaw.desktop.settings;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ManagedWorktreeArtifactKind;
import com.javaclaw.api.ManagedWorktreeState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedWorktreeSettingsPresenterTest {
    @Test
    void 恢复中心只生成Artifact并在验证Backup后危险清理() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ManagedWorktreeSettingsPresenter presenter = new ManagedWorktreeSettingsPresenter(gateway);
        AtomicReference<ManagedWorktreeSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();
        assertEquals(
                ManagedWorktreeState.COMPLETED,
                latest.get().selected().orElseThrow().state());

        presenter.exportPatch();
        assertEquals(
                ManagedWorktreeArtifactKind.PATCH,
                latest.get().artifact().orElseThrow().kind());
        assertFalse(latest.get().selected().orElseThrow().backup().isPresent());

        presenter.backup();
        assertEquals(
                ManagedWorktreeArtifactKind.BACKUP,
                latest.get().artifact().orElseThrow().kind());
        assertTrue(latest.get().selected().orElseThrow().backup().isPresent());

        presenter.cleanup();
        assertTrue(latest.get().worktrees().isEmpty());
        presenter.includeCleaned(true);
        assertEquals(
                ManagedWorktreeState.CLEANED,
                latest.get().selected().orElseThrow().state());
    }
}
