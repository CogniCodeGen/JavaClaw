package com.javaclaw.plugin;

import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.skill.SkillManager;

import java.util.Objects;

/** Narrow workspace-scoped capabilities available to the restricted plugin host. */
public record PluginWorkspaceServices(
        WorkspaceContext workspace,
        ScheduleApplicationService schedules,
        SkillManager skills,
        MemoryService memory) {
    public PluginWorkspaceServices {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(schedules, "schedules");
        Objects.requireNonNull(skills, "skills");
        Objects.requireNonNull(memory, "memory");
    }
}
