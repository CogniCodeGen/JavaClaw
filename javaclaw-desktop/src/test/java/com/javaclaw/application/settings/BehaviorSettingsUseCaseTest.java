package com.javaclaw.application.settings;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GeneralSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GepaSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SkillEvolutionSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.Snapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorSettingsUseCaseTest {

    @Test
    void validatesGepaAtomicallyAndRequestsRuntimeRefresh() {
        FakePort port = new FakePort(snapshot());
        BehaviorSettingsUseCase useCase = new BehaviorSettingsUseCase(port);
        GepaSettings changed = new GepaSettings(false, 6, 4.25, true, 4);

        var result = useCase.saveGepa(changed);

        assertEquals(changed, result.snapshot().gepa());
        assertEquals(1, port.gepaSaves);
        assertTrue(result.runtimeRefreshRequired());

        assertThrows(ValidationException.class,
                () -> useCase.saveGepa(new GepaSettings(true, 0, 3, true, 2)));
        assertThrows(ValidationException.class,
                () -> useCase.saveGepa(new GepaSettings(true, 3, Double.NaN, true, 2)));
        assertEquals(1, port.gepaSaves, "校验失败不得产生部分写入");
    }

    @Test
    void validatesSkillEvolutionModeAndThresholds() {
        FakePort port = new FakePort(snapshot());
        BehaviorSettingsUseCase useCase = new BehaviorSettingsUseCase(port);
        SkillEvolutionSettings changed =
                new SkillEvolutionSettings("auto", 12, 0.8, false, true);

        var result = useCase.saveSkillEvolution(changed);

        assertEquals(changed, result.snapshot().skillEvolution());
        assertEquals(1, port.skillSaves);
        assertFalse(result.runtimeRefreshRequired());
        assertThrows(ValidationException.class, () -> useCase.saveSkillEvolution(
                new SkillEvolutionSettings("unknown", 5, 0.6, true, false)));
        assertThrows(ValidationException.class, () -> useCase.saveSkillEvolution(
                new SkillEvolutionSettings("suggest", 5, 1.1, true, false)));
        assertEquals(1, port.skillSaves);
    }

    @Test
    void savesGeneralBehaviorWithoutRuntimeRefresh() {
        FakePort port = new FakePort(snapshot());
        BehaviorSettingsUseCase useCase = new BehaviorSettingsUseCase(port);

        var result = useCase.saveGeneral(new GeneralSettings(false, true));

        assertEquals(new GeneralSettings(false, true), result.snapshot().general());
        assertEquals(1, port.generalSaves);
        assertFalse(result.runtimeRefreshRequired());
    }

    private static Snapshot snapshot() {
        return new Snapshot(new GepaSettings(true, 3, 3.5, true, 2),
                new SkillEvolutionSettings("suggest", 5, 0.6, true, false),
                new GeneralSettings(true, false), "fake-agent-config");
    }

    private static final class FakePort implements BehaviorSettingsPort {
        private Snapshot snapshot;
        private int gepaSaves;
        private int skillSaves;
        private int generalSaves;

        private FakePort(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public Snapshot load() { return snapshot; }

        @Override public void saveGepa(GepaSettings value) {
            gepaSaves++;
            snapshot = new Snapshot(value, snapshot.skillEvolution(), snapshot.general(),
                    snapshot.storageDescription());
        }

        @Override public void saveSkillEvolution(SkillEvolutionSettings value) {
            skillSaves++;
            snapshot = new Snapshot(snapshot.gepa(), value, snapshot.general(),
                    snapshot.storageDescription());
        }

        @Override public void saveGeneral(GeneralSettings value) {
            generalSaves++;
            snapshot = new Snapshot(snapshot.gepa(), snapshot.skillEvolution(), value,
                    snapshot.storageDescription());
        }
    }
}
