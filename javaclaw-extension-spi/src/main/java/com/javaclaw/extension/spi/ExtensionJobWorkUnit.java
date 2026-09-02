package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * 扩展为下一次推进声明的确定性工作意图。
 *
 * @param unitId Job 内唯一且重试稳定的单元 ID
 * @param intent 执行前必须持久化的规范意图
 */
public record ExtensionJobWorkUnit(String unitId, CanonicalPayload intent) {
    /** 校验工作意图。 */
    public ExtensionJobWorkUnit {
        unitId = Objects.requireNonNull(unitId, "unitId").strip();
        if (unitId.isEmpty() || unitId.length() > 240) {
            throw new IllegalArgumentException("unitId length must be between 1 and 240");
        }
        Objects.requireNonNull(intent, "intent");
    }
}
