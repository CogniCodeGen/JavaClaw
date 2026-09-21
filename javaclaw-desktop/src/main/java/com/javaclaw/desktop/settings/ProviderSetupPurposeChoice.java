package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.Set;

import com.javaclaw.api.ProviderModelPurpose;

/** 模型用途的明确选择；未知用途不根据模型名称或 ID 推断。 */
enum ProviderSetupPurposeChoice {
    UNKNOWN("请选择用途", Set.of()),
    CHAT("对话", Set.of(ProviderModelPurpose.CHAT)),
    EMBEDDING("向量", Set.of(ProviderModelPurpose.EMBEDDING)),
    BOTH("对话和向量", Set.of(ProviderModelPurpose.CHAT, ProviderModelPurpose.EMBEDDING));

    private final String label;
    private final Set<ProviderModelPurpose> purposes;

    ProviderSetupPurposeChoice(String label, Set<ProviderModelPurpose> purposes) {
        this.label = label;
        this.purposes = purposes;
    }

    String label() {
        return label;
    }

    Set<ProviderModelPurpose> purposes() {
        return purposes;
    }

    static ProviderSetupPurposeChoice from(Set<ProviderModelPurpose> purposes) {
        return Arrays.stream(values())
                .filter(choice -> choice.purposes.equals(purposes))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
