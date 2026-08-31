package com.javaclaw.agent.automation;

import com.javaclaw.core.api.ProfileKind;

/** 自动化执行类别，与统一 ProfileKind 对齐，不拥有独立执行引擎。 */
public enum AutomationKind {
    LOOP(ProfileKind.LOOP),
    WORKFLOW(ProfileKind.WORKFLOW),
    SDD(ProfileKind.SDD);

    private final ProfileKind profileKind;

    AutomationKind(ProfileKind profileKind) {
        this.profileKind = profileKind;
    }

    /** 返回该自动化类别要求的 ProfileKind，用于启动前的配置一致性检查。 */
    public ProfileKind profileKind() {
        return profileKind;
    }
}
