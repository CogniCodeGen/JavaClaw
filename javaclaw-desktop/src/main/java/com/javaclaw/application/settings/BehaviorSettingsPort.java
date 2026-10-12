package com.javaclaw.application.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GeneralSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GepaSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SkillEvolutionSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.Snapshot;

/** 工作区智能行为设置的同步持久化端口。 */
public interface BehaviorSettingsPort {

    Snapshot load();

    void saveGepa(GepaSettings settings);

    void saveSkillEvolution(SkillEvolutionSettings settings);

    void saveGeneral(GeneralSettings settings);
}
