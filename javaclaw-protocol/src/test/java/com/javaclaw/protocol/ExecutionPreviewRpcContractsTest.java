package com.javaclaw.protocol;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionBlocker;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPreviewRpcContractsTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 轻量预览使用只读方法且保留精确版本与关闭思考() {
        var payload = new ExecutionRpcContracts.PreviewPayload(
                WorkspaceId.random(), Optional.of(ThreadId.random()), ExecutionOverrides.empty());
        var preview = new ExecutionPreview(
                Optional.of(new AgentRoleRef("default", 1)),
                Optional.of(new ProviderRef("connection", 3, "model")),
                Optional.of(ReasoningPreference.NONE),
                true,
                true,
                List.of(),
                List.of());

        assertEquals(payload, json.decode(json.encode(payload), ExecutionRpcContracts.PreviewPayload.class));
        assertEquals(preview, json.decode(json.encode(preview), ExecutionPreview.class));
        assertTrue(preview.ready());
        assertEquals(
                RpcMethodKind.QUERY,
                MethodCatalog.require("execution/preview", new NegotiatedCapabilities(Set.of(), Set.of()))
                        .kind());
    }

    @Test
    void 各阻塞原因可往返且空配置不会被误报为就绪() {
        for (ExecutionBlocker.Code code : ExecutionBlocker.Code.values()) {
            var preview = new ExecutionPreview(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    false,
                    List.of(),
                    List.of(new ExecutionBlocker(code, "配置需要更新")));
            assertEquals(preview, json.decode(json.encode(preview), ExecutionPreview.class));
            assertFalse(preview.ready());
        }
    }

    @Test
    void 发布Schema封闭结果字段且不包含消息与提示词() throws Exception {
        try (var stream = getClass().getResourceAsStream("/schema/execution-preview-v3.schema.json")) {
            assertNotNull(stream);
            var schema = json.mapper().readTree(stream);
            Set<String> fields = new HashSet<>();
            schema.at("/$defs/preview/required").forEach(field -> fields.add(field.textValue()));
            var preview = new ExecutionPreview(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    false,
                    List.of(),
                    List.of(new ExecutionBlocker(ExecutionBlocker.Code.MODEL_REQUIRED, "请选择模型")));
            assertEquals(fields, json.fieldNames(json.encode(preview)));
            assertFalse(schema.at("/$defs/preview/additionalProperties").asBoolean());
            assertFalse(schema.toString().contains("prompt"));
            assertFalse(schema.toString().contains("secret"));
        }
    }
}
