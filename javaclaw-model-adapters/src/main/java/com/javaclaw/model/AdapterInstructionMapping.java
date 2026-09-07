package com.javaclaw.model;

import java.util.List;
import java.util.stream.Collectors;

import com.javaclaw.runtime.ModelInstructions;

/** Provider 缺少 developer role 时的唯一合并边界；固定保留平台、开发者和响应契约顺序。 */
final class AdapterInstructionMapping {
    private AdapterInstructionMapping() {}

    static String merged(ModelInstructions instructions) {
        return List.of(
                        instructions.systemInstruction(),
                        instructions.developerInstructions(),
                        instructions.responseContract())
                .stream()
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n\n"));
    }
}
