package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.protocol.InputJobRpcContracts;

/**
 * Extension Job 的权威详情与最近写冲突。
 *
 * @param value Job 和脱敏工作单元
 * @param revisionConflict 最近一次动作是否发生乐观锁冲突
 */
public record AutomationJobDetail(Optional<InputJobRpcContracts.JobReadResult> value, boolean revisionConflict) {
    /** 校验可选详情。 */
    public AutomationJobDetail {
        value = Objects.requireNonNull(value, "value");
    }

    /** @return 尚未选择 Job 的空详情 */
    public static AutomationJobDetail empty() {
        return new AutomationJobDetail(Optional.empty(), false);
    }
}
