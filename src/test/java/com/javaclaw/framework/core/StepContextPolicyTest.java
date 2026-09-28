package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StepContextPolicyTest {

    @Test
    void 缺少配置时使用保守默认值() {
        assertEquals(StepContextPolicy.DEFAULT,
                StepContextPolicy.from(JsonNodeFactory.instance.missingNode()));
        assertEquals(StepContextPolicy.DEFAULT,
                StepContextPolicy.from(JsonNodeFactory.instance.objectNode()));
        assertEquals(StepContextPolicy.DEFAULT,
                StepContextPolicy.from(JsonNodeFactory.instance.textNode("enabled")));
    }

    @Test
    void 合法配置覆盖默认值() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode()
                .put("maxMessageCharacters", 4_000)
                .put("maxToolSchemaCharacters", 200_000)
                .put("retainedToolExchanges", 32)
                .put("maxToolResultCharacters", 1_000)
                .put("maxTools", 256);

        assertEquals(new StepContextPolicy(4_000, 200_000, 32, 1_000, 256),
                StepContextPolicy.from(configuration));
    }

    @Test
    void 非整数配置项回退到默认值() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode()
                .put("maxMessageCharacters", "48000")
                .put("maxToolSchemaCharacters", true)
                .put("retainedToolExchanges", 8);

        StepContextPolicy actual = StepContextPolicy.from(configuration);

        assertEquals(StepContextPolicy.DEFAULT.maxMessageCharacters(), actual.maxMessageCharacters());
        assertEquals(StepContextPolicy.DEFAULT.maxToolSchemaCharacters(), actual.maxToolSchemaCharacters());
        assertEquals(8, actual.retainedToolExchanges());
    }

    @Test
    void 越界配置被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> new StepContextPolicy(3_999, 48_000, 4, 16_000, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new StepContextPolicy(48_000, 48_000, 0, 16_000, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new StepContextPolicy(48_000, 48_000, 4, 999, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new StepContextPolicy(48_000, 48_000, 4, 16_000, 257));
        var oversized = JsonNodeFactory.instance.objectNode()
                .put("maxTools", 4_294_967_298L);
        assertThrows(IllegalArgumentException.class,
                () -> StepContextPolicy.from(oversized));
    }
}
