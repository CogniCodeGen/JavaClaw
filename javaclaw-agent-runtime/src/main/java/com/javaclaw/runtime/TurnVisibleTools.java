package com.javaclaw.runtime;

import java.util.LinkedHashSet;
import java.util.List;

import com.javaclaw.api.ToolIdentity;

/**
 * Turn 内已经向模型公开的冻结工具身份。
 *
 * @param identities 去重后的工具身份
 */
public record TurnVisibleTools(List<ToolIdentity> identities) {
    /** 保留首次出现顺序并拒绝空元素。 */
    public TurnVisibleTools {
        LinkedHashSet<ToolIdentity> unique = new LinkedHashSet<>(identities);
        if (unique.contains(null)) {
            throw new IllegalArgumentException("visible tools must not contain null");
        }
        identities = List.copyOf(unique);
    }

    /**
     * 返回空集合。
     *
     * @return 空集合
     */
    public static TurnVisibleTools empty() {
        return new TurnVisibleTools(List.of());
    }
}
