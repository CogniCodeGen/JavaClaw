package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;

/** 设置中心测试使用的最小精确 Schedule 定义。 */
final class TestScheduleDefinitions {
    private TestScheduleDefinitions() {}

    /** @return 引用指定 Agent Role 的固定间隔 Turn Schedule */
    static ScheduleContracts.Definition turn(AgentRole profile) {
        AgentRoleRef reference = new AgentRoleRef(profile.id(), profile.revision());
        ScheduleContracts.TurnTemplate template = new ScheduleContracts.TurnTemplate(
                new com.javaclaw.api.ExecutionOverrides(
                        Optional.of(reference),
                        Optional.empty(),
                        Optional.of(new com.javaclaw.api.PermissionProfileRef("standard", 1)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                "Nightly review",
                "执行只读审查。",
                new OrchestrationContracts.ExecutionBudget(2, 8_000, 2_000, 10));
        Instant updatedAt = Instant.parse("2026-09-01T01:00:00Z");
        return new ScheduleContracts.Definition(
                "nightly-review",
                3,
                "每夜审查",
                true,
                ScheduleContracts.Timing.fixed(Duration.ofDays(1), updatedAt.plus(Duration.ofDays(1))),
                ScheduleContracts.Target.turn(template),
                ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                updatedAt);
    }
}
