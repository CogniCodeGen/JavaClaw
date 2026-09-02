package com.javaclaw.client.extension;

import java.util.Objects;

/** 九个内置扩展的强类型领域 facade 集合。 */
public final class BuiltinExtensionClients {
    private final PlanClient plans;
    private final LoopClient loops;
    private final WorkflowClient workflows;
    private final SddClient sdd;
    private final ScheduleClient schedules;
    private final MemoryClient memories;
    private final KnowledgeClient knowledge;
    private final SkillClient skills;
    private final SiteClient sites;

    /**
     * 创建 facade 集合。
     *
     * @param extensions 通用 Extension 客户端
     */
    public BuiltinExtensionClients(ExtensionClient extensions) {
        Objects.requireNonNull(extensions, "extensions");
        plans = new PlanClient(extensions);
        loops = new LoopClient(extensions);
        workflows = new WorkflowClient(extensions);
        sdd = new SddClient(extensions);
        schedules = new ScheduleClient(extensions);
        memories = new MemoryClient(extensions);
        knowledge = new KnowledgeClient(extensions);
        skills = new SkillClient(extensions);
        sites = new SiteClient(extensions);
    }

    /** @return Plan facade */
    public PlanClient plans() {
        return plans;
    }

    /** @return Loop facade */
    public LoopClient loops() {
        return loops;
    }

    /** @return Workflow facade */
    public WorkflowClient workflows() {
        return workflows;
    }

    /** @return SDD facade */
    public SddClient sdd() {
        return sdd;
    }

    /** @return Schedule facade */
    public ScheduleClient schedules() {
        return schedules;
    }

    /** @return Memory facade */
    public MemoryClient memories() {
        return memories;
    }

    /** @return Knowledge facade */
    public KnowledgeClient knowledge() {
        return knowledge;
    }

    /** @return Skill facade */
    public SkillClient skills() {
        return skills;
    }

    /** @return Site facade */
    public SiteClient sites() {
        return sites;
    }
}
