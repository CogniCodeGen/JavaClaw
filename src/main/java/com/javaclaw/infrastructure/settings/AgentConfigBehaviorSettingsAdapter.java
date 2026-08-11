package com.javaclaw.infrastructure.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GeneralSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GepaSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SkillEvolutionSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.Snapshot;
import com.javaclaw.application.settings.BehaviorSettingsPort;
import com.javaclaw.config.AgentConfig;

import java.util.Objects;

/** 将工作区 AgentConfig 适配为不可变行为设置快照。 */
public final class AgentConfigBehaviorSettingsAdapter implements BehaviorSettingsPort {

    private final AgentConfig config;

    public AgentConfigBehaviorSettingsAdapter(AgentConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public synchronized Snapshot load() {
        return new Snapshot(
                new GepaSettings(config.isGepaGoalEnabled(), config.getGepaEvalInterval(),
                        config.getGepaEvalThreshold(), config.isGepaPlanAdaptive(),
                        config.getGepaFeedbackMaxRounds()),
                new SkillEvolutionSettings(config.getSkillEvolutionMode(),
                        config.getSkillEvolutionMinTools(),
                        config.getSkillEvolutionSuccessThreshold(),
                        config.isSkillNudgeEnabled(), config.isSkillBundlesEnabled()),
                new GeneralSettings(config.isTrayMinimizeOnClose(),
                        config.isTaskRiskAutoApproveEnabled()),
                config.getConfigFilePath());
    }

    @Override
    public synchronized void saveGepa(GepaSettings value) {
        config.setGepaGoalEnabled(value.goalDecompositionEnabled());
        config.setGepaEvalInterval(value.evaluationInterval());
        config.setGepaEvalThreshold(value.evaluationThreshold());
        config.setGepaPlanAdaptive(value.adaptivePlanningEnabled());
        config.setGepaFeedbackMaxRounds(value.feedbackMaxRounds());
        config.save();
    }

    @Override
    public synchronized void saveSkillEvolution(SkillEvolutionSettings value) {
        config.setSkillEvolutionMode(value.mode());
        config.setSkillEvolutionMinTools(value.minimumToolCalls());
        config.setSkillEvolutionSuccessThreshold(value.successThreshold());
        config.setSkillNudgeEnabled(value.nudgeEnabled());
        config.setSkillBundlesEnabled(value.bundlesEnabled());
        config.save();
    }

    @Override
    public synchronized void saveGeneral(GeneralSettings value) {
        config.setTrayMinimizeOnClose(value.minimizeToTrayOnClose());
        config.setTaskRiskAutoApproveEnabled(value.taskRiskAutoApproveEnabled());
        config.save();
    }
}
