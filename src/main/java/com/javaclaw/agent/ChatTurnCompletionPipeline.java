package com.javaclaw.agent;

import com.javaclaw.agent.execution.ExecutionMonitor;
import com.javaclaw.application.turn.TurnPipeline;
import com.javaclaw.application.turn.TurnStage;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.skill.SkillRuntimeServices;
import com.javaclaw.skill.curation.SkillCurator;
import com.javaclaw.util.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/** Runs best-effort post-delivery memory, skill distillation and usage attribution. */
final class ChatTurnCompletionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnCompletionPipeline.class);

    private final AgentRuntime runtime;
    private final MemoryService memory;
    private final SkillCurator curator;
    private final SkillRuntimeServices skills;
    private final ExecutionMonitor executions;

    ChatTurnCompletionPipeline(
            AgentRuntime runtime,
            MemoryService memory,
            SkillCurator curator,
            SkillRuntimeServices skills,
            ExecutionMonitor executions) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.curator = Objects.requireNonNull(curator, "curator");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    void persist(String userInput, String assistantReply) {
        CompletedTurn state = new CompletedTurn(userInput, assistantReply);
        TurnPipeline<CompletedTurn> pipeline = new TurnPipeline<>(List.of(
                new TurnStage<>("memory", turn -> memory.rememberTurn(
                        "chat", turn.userInput(), turn.assistantReply(), null)),
                new TurnStage<>("skills", this::distillSkill)));
        try {
            pipeline.execute(state);
        } catch (RuntimeException failure) {
            log.warn("轮后记忆/技能流水线失败，不影响已交付回复: {}",
                    failure.getMessage(), failure);
        }
    }

    boolean successful() {
        return executions.getTraceCount() == 0
                || executions.successRate()
                >= runtime.getConfig().getSkillEvolutionSuccessThreshold();
    }

    void recordSkillOutcome(List<String> injectedSkills, boolean success) {
        try {
            if (injectedSkills != null && !injectedSkills.isEmpty()) {
                skills.usage().recordTurnOutcome(injectedSkills, success);
            }
        } catch (Exception failure) {
            log.debug("记录技能轮次成败失败（忽略）: {}", failure.getMessage());
        }
    }

    private void distillSkill(CompletedTurn turn) {
        if (SensitiveDataRedactor.containsLikelyCredential(turn.userInput())
                || SensitiveDataRedactor.containsLikelyCredential(turn.assistantReply())) {
            log.warn("本轮包含疑似凭据，已跳过技能蒸馏");
            return;
        }
        curator.distillFromChatTurn(turn.userInput(), turn.assistantReply(),
                        executions.getTraces(), executions.successRate())
                .subscribe();
    }

    private record CompletedTurn(String userInput, String assistantReply) { }
}
