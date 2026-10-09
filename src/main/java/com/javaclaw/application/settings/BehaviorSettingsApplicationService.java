package com.javaclaw.application.settings;

import java.util.Objects;
import com.javaclaw.desktop.api.DesktopInputPolicy;

/** 智能行为与桌面常驻设置的同步应用入口。 */
public interface BehaviorSettingsApplicationService {

    Snapshot snapshot();

    SaveResult saveGepa(GepaSettings settings);

    SaveResult saveSkillEvolution(SkillEvolutionSettings settings);

    SaveResult saveGeneral(GeneralSettings settings);

    record GepaSettings(
            boolean goalDecompositionEnabled,
            int evaluationInterval,
            double evaluationThreshold,
            boolean adaptivePlanningEnabled,
            int feedbackMaxRounds) {
    }

    record SkillEvolutionSettings(
            String mode,
            int minimumToolCalls,
            double successThreshold,
            boolean nudgeEnabled,
            boolean bundlesEnabled) {
        public SkillEvolutionSettings {
            mode = normalize(mode);
        }
    }

    record GeneralSettings(
            boolean minimizeToTrayOnClose,
            boolean computerAppAccessEnabled,
            DesktopInputPolicy inputPolicy) {
        public GeneralSettings {
            inputPolicy = Objects.requireNonNullElse(inputPolicy, DesktopInputPolicy.BACKGROUND_STRICT);
        }
        public GeneralSettings(boolean minimizeToTrayOnClose, boolean computerAppAccessEnabled) {
            this(minimizeToTrayOnClose, computerAppAccessEnabled, DesktopInputPolicy.BACKGROUND_STRICT);
        }
    }

    record Snapshot(
            GepaSettings gepa,
            SkillEvolutionSettings skillEvolution,
            GeneralSettings general,
            String storageDescription) {
        public Snapshot {
            gepa = Objects.requireNonNull(gepa, "gepa");
            skillEvolution = Objects.requireNonNull(skillEvolution, "skillEvolution");
            general = Objects.requireNonNull(general, "general");
            storageDescription = normalize(storageDescription);
        }
    }

    record SaveResult(Snapshot snapshot, String message, boolean runtimeRefreshRequired) {
        public SaveResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            message = normalize(message);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
