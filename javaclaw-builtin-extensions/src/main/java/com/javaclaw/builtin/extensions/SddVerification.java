package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;

import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.OrchestratedToolEvidence;

/** 从平台持久 ToolResult 判断 SDD 验收；模型文本从不参与。 */
final class SddVerification {
    private SddVerification() {}

    static boolean satisfied(
            List<OrchestratedToolEvidence> evidence,
            SddContracts.VerificationRule rule,
            ExtensionPayloadCodec payloads) {
        return evidence.stream()
                .filter(item -> item.toolName().equals(rule.toolName()))
                .filter(OrchestratedToolEvidence::successful)
                .reduce((first, second) -> second)
                .map(item -> matches(item, rule, payloads))
                .orElse(false);
    }

    private static boolean matches(
            OrchestratedToolEvidence evidence, SddContracts.VerificationRule rule, ExtensionPayloadCodec payloads) {
        Object output = payloads.decode(evidence.output(), Object.class);
        return switch (rule.kind()) {
            case TOOL_EXIT_CODE ->
                exitCode(output)
                        .filter(rule.expectedExitCode().orElseThrow()::equals)
                        .isPresent();
            case TOOL_FIELD_ASSERTION -> {
                Object value = pointer(output, rule.fieldPointer().orElseThrow());
                yield value != null
                        && payloads.encode(Map.of("value", value))
                                .equals(rule.expectedValue().orElseThrow());
            }
        };
    }

    private static java.util.Optional<Integer> exitCode(Object output) {
        Object value = pointer(output, "/exitCode");
        return value instanceof Number number ? java.util.Optional.of(number.intValue()) : java.util.Optional.empty();
    }

    private static Object pointer(Object root, String pointer) {
        Object current = root;
        for (String token : pointer.substring(1).split("/", -1)) {
            String key = token.replace("~1", "/").replace("~0", "~");
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }
}
