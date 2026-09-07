package com.javaclaw.client.extension;

import java.util.Optional;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionOverrides;

/** 自动化测试显式构造独立选择和已冻结配置。 */
final class AutomationV6Fixtures {
    private AutomationV6Fixtures() {}

    static ExecutionOverrides selection(AgentRoleRef role) {
        return new ExecutionOverrides(
                Optional.of(role),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
