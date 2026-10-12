package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OnDemandContextPolicyTest {

    @Test
    void 缺少限制配置时使用默认值() {
        assertEquals(new OnDemandContextPolicy(2, 3, 32, 8_000, 12_000, 8),
                OnDemandContextPolicy.from(JsonNodeFactory.instance.objectNode()));
        assertEquals(OnDemandContextPolicy.CANDIDATE_TOOL_SELECTION_VERSION,
                OnDemandContextPolicy.DEFAULT.toolSelectionVersion());
    }

    @Test
    void 编译配置可覆盖默认值() {
        var configuration = JsonNodeFactory.instance.objectNode()
                .put("searches", 4)
                .put("fetches", 5)
                .put("candidates", 20)
                .put("plannerInputChars", 16_000)
                .put("selectedBodyChars", 24_000)
                .put("selectedTools", 12);

        assertEquals(new OnDemandContextPolicy(4, 5, 20, 16_000, 24_000, 12),
                OnDemandContextPolicy.from(configuration));
    }

    @Test
    void 新编译配置始终使用候选Id契约() {
        var configuration = JsonNodeFactory.instance.objectNode();
        assertEquals(OnDemandContextPolicy.CANDIDATE_TOOL_SELECTION_VERSION,
                OnDemandContextPolicy.from(configuration).toolSelectionVersion());

        configuration.put("toolSelectionVersion", 1);
        assertEquals(OnDemandContextPolicy.CANDIDATE_TOOL_SELECTION_VERSION,
                OnDemandContextPolicy.from(configuration).toolSelectionVersion());
    }

    @Test
    void 缺少工具契约版本的计划被拒绝() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var persisted = json.valueToTree(OnDemandContextPolicy.DEFAULT);
        ((com.fasterxml.jackson.databind.node.ObjectNode) persisted)
                .remove("toolSelectionVersion");

        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> json.treeToValue(persisted, OnDemandContextPolicy.class));
        assertEquals(OnDemandContextPolicy.CANDIDATE_TOOL_SELECTION_VERSION,
                json.readValue(json.writeValueAsBytes(OnDemandContextPolicy.DEFAULT),
                        OnDemandContextPolicy.class).toolSelectionVersion());
    }

    @Test
    void 越界限制在计划编译前被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> new OnDemandContextPolicy(0, 3, 32, 8_000, 12_000, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new OnDemandContextPolicy(2, 65, 32, 8_000, 12_000, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new OnDemandContextPolicy(2, 3, 32, 8_000, 12_000, 257));
        assertThrows(IllegalArgumentException.class,
                () -> new OnDemandContextPolicy(2, 3, 32, 8_000, 12_000, 8, 3));
        var oversized = JsonNodeFactory.instance.objectNode()
                .put("searches", 4_294_967_298L);
        assertThrows(IllegalArgumentException.class,
                () -> OnDemandContextPolicy.from(oversized));
    }
}
