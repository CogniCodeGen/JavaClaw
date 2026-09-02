package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;

import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.OrchestratedToolEvidence;

/** 从平台持久 ToolResult 证据验证 Loop 规则，绝不读取模型自述。 */
final class LoopVerification {
    private LoopVerification() {}

    static void requireSatisfied(
            List<OrchestratedToolEvidence> evidence,
            LoopContracts.VerificationRule rule,
            ExtensionPayloadCodec payloads) {
        if (rule.kind() == LoopContracts.VerificationKind.USER_CONFIRMATION) {
            throw new IllegalArgumentException("user confirmation is not tool evidence");
        }
        OrchestratedToolEvidence matching = evidence.stream()
                .filter(item -> item.toolName().equals(rule.toolName().orElseThrow()))
                .filter(OrchestratedToolEvidence::successful)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("Loop verification tool evidence is missing"));
        Object output = payloads.decode(matching.output(), Object.class);
        boolean satisfied =
                switch (rule.kind()) {
                    case TOOL_EXIT_CODE ->
                        exitCode(output) == rule.expectedExitCode().orElseThrow();
                    case TOOL_FIELD_ASSERTION ->
                        expectedValue(
                                        payloads,
                                        pointer(output, rule.fieldPointer().orElseThrow()))
                                .equals(rule.expectedValue().orElseThrow());
                    case USER_CONFIRMATION ->
                        throw new IllegalArgumentException("user confirmation is not tool evidence");
                };
        if (!satisfied) {
            throw new IllegalStateException("Loop verification evidence did not satisfy the rule");
        }
    }

    private static com.javaclaw.api.CanonicalPayload expectedValue(ExtensionPayloadCodec payloads, Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new IllegalStateException("Loop verification field must be a scalar value");
        }
        return payloads.encode(Map.of("value", value));
    }

    private static int exitCode(Object output) {
        Object value = pointer(output, "/exitCode");
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Loop exit-code evidence is not numeric");
        }
        return number.intValue();
    }

    private static Object pointer(Object root, String pointer) {
        Object current = root;
        for (String token : pointer.substring(1).split("/", -1)) {
            String key = token.replace("~1", "/").replace("~0", "~");
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) {
                throw new IllegalStateException("Loop verification JSON Pointer does not exist");
            }
            current = map.get(key);
        }
        return current;
    }
}
