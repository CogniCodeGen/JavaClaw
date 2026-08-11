package com.javaclaw.application.settings;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GeneralSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GepaSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SaveResult;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SkillEvolutionSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.Snapshot;

import java.util.Objects;
import java.util.Set;

/** GEPA、技能进化与通用行为设置用例。 */
public final class BehaviorSettingsUseCase implements BehaviorSettingsApplicationService {

    private static final Set<String> SKILL_MODES = Set.of("off", "suggest", "auto");
    private final BehaviorSettingsPort settings;

    public BehaviorSettingsUseCase(BehaviorSettingsPort settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Snapshot snapshot() {
        return settings.load();
    }

    @Override
    public SaveResult saveGepa(GepaSettings value) {
        Objects.requireNonNull(value, "settings");
        range(value.evaluationInterval(), 1, 20, "评估间隔");
        range(value.evaluationThreshold(), 1, 5, "评估通过阈值");
        range(value.feedbackMaxRounds(), 0, 10, "最大调整轮次");
        settings.saveGepa(value);
        return saved("✓ 已保存，下一轮对话生效", true);
    }

    @Override
    public SaveResult saveSkillEvolution(SkillEvolutionSettings value) {
        Objects.requireNonNull(value, "settings");
        if (!SKILL_MODES.contains(value.mode())) {
            throw new ValidationException("技能进化模式无效");
        }
        range(value.minimumToolCalls(), 1, 50, "最小工具调用数");
        range(value.successThreshold(), 0, 1, "成功率门槛");
        settings.saveSkillEvolution(value);
        return saved("✓ 已保存，下一轮对话生效", false);
    }

    @Override
    public SaveResult saveGeneral(GeneralSettings value) {
        settings.saveGeneral(Objects.requireNonNull(value, "settings"));
        return saved("✓ 已保存", false);
    }

    private SaveResult saved(String message, boolean refresh) {
        return new SaveResult(snapshot(), message, refresh);
    }

    private static void range(int value, int min, int max, String label) {
        if (value < min || value > max) {
            throw new ValidationException(label + "需在 " + min + " ~ " + max + " 之间");
        }
    }

    private static void range(double value, double min, double max, String label) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new ValidationException(label + "需在 " + min + " ~ " + max + " 之间");
        }
    }
}
