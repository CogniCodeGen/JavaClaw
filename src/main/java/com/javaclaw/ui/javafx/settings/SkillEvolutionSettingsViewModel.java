package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SkillEvolutionSettings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.Set;

/** 技能进化设置页的纯 JavaFX 状态。 */
public final class SkillEvolutionSettingsViewModel {

    private static final Set<String> MODES = Set.of("off", "suggest", "auto");
    private final StringProperty mode = new SimpleStringProperty("suggest");
    private final StringProperty modeHint = new SimpleStringProperty("");
    private final StringProperty minimumToolCalls = new SimpleStringProperty("");
    private final StringProperty successThreshold = new SimpleStringProperty("");
    private final BooleanProperty nudgeEnabled = new SimpleBooleanProperty();
    private final BooleanProperty bundlesEnabled = new SimpleBooleanProperty();
    private final StringProperty error = new SimpleStringProperty("");
    private final BooleanProperty busy = new SimpleBooleanProperty();

    public SkillEvolutionSettingsViewModel() {
        mode.addListener((ignored, previous, value) -> updateModeHint(value));
        updateModeHint(mode.get());
    }

    public void load(SkillEvolutionSettings settings) {
        setMode(settings.mode());
        minimumToolCalls.set(Integer.toString(settings.minimumToolCalls()));
        successThreshold.set(Double.toString(settings.successThreshold()));
        nudgeEnabled.set(settings.nudgeEnabled());
        bundlesEnabled.set(settings.bundlesEnabled());
        error.set("");
    }

    public void setMode(String value) {
        mode.set(MODES.contains(value) ? value : "suggest");
    }

    public StringProperty modeProperty() { return mode; }
    public StringProperty modeHintProperty() { return modeHint; }
    public StringProperty minimumToolCallsProperty() { return minimumToolCalls; }
    public StringProperty successThresholdProperty() { return successThreshold; }
    public BooleanProperty nudgeEnabledProperty() { return nudgeEnabled; }
    public BooleanProperty bundlesEnabledProperty() { return bundlesEnabled; }
    public StringProperty errorProperty() { return error; }
    public BooleanProperty busyProperty() { return busy; }

    private void updateModeHint(String value) {
        modeHint.set(switch (value == null ? "suggest" : value) {
            case "off" -> "skill_manage 工具拒绝写入，SkillCurator 不蒸馏。";
            case "auto" -> "直接落盘并 Toast；但你手动改过的技能仍强制降级为提案，绝不静默覆盖。";
            default -> "变更先入「技能中心 → 待审提案」队列，人工采纳后才落盘。"
                    + "user-modified 技能受保护。";
        });
    }
}
