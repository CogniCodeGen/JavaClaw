package com.javaclaw.builtin.extensions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ViewInitialSelection;
import com.javaclaw.extension.spi.ViewQueryResult;

/** 学习配置的精确引用回显；初选只提供展示提示，不替代当前目录行或提升其权限。 */
final class MemoryLearningSelectionView {
    private final ExtensionPayloadCodec payloads;

    MemoryLearningSelectionView(ExtensionPayloadCodec payloads) {
        this.payloads = payloads;
    }

    ExtensionResponse decorate(ExtensionResponse response, ExtensionExecutionContext context) throws Exception {
        var result = payloads.decode(response.payload(), ViewQueryResult.class);
        var saved = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> transaction
                                .get(
                                        MemoryLearningState.definitions(context.workspaceId()),
                                        MemoryLearningState.DEFINITION_ID)
                                .map(value ->
                                        payloads.decode(value.payload(), MemoryV3Contracts.LearningDefinition.class)));
        var selection = saved.flatMap(definition -> selection(result.dataSourceId(), definition.execution()));
        if (selection.isEmpty()) {
            return response;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<?, ?> originalValues = payloads.decode(result.values(), Map.class);
        originalValues.forEach((key, value) -> values.put(key.toString(), value));
        // 不能写入 role/provider/permissionProfile 同名标量，否则会遮蔽用户后来选择的目录行。
        values.put(ViewInitialSelection.VALUES_KEY, selection.orElseThrow());
        var decorated = new ViewQueryResult(
                result.dataSourceId(),
                result.rows(),
                payloads.encode(values),
                result.nextCursor(),
                result.hasMore(),
                result.revision());
        return new ExtensionResponse(payloads.encode(decorated), response.revision());
    }

    private Optional<ViewInitialSelection> selection(String source, ExecutionOverrides execution) {
        return switch (source) {
            case "roles" -> execution.role().map(value -> initial(source, value.id(), value.revision()));
            case "providers" ->
                execution
                        .provider()
                        .map(value ->
                                initial(source, value.endpointId() + "/" + value.model(), value.endpointRevision()));
            case "permissions" ->
                execution.permissionProfile().map(value -> initial(source, value.id(), value.version()));
            default -> Optional.empty();
        };
    }

    private static ViewInitialSelection initial(String source, String key, long revision) {
        return new ViewInitialSelection(ViewInitialSelection.VERSION, source, key, "revision", revision);
    }

    static void savedValues(Map<String, Object> values, ExecutionOverrides execution) {
        execution.approvalPolicy().ifPresent(policy -> values.put("approvalPolicy", policy));
        execution.reasoning().ifPresent(reasoning -> values.put("reasoning", reasoning));
        execution.role().ifPresent(role -> values.put("role", role));
        execution.provider().ifPresent(provider -> values.put("provider", provider));
        execution.permissionProfile().ifPresent(permission -> values.put("permissionProfile", permission));
        values.put("savedExecution", description(execution));
    }

    private static String description(ExecutionOverrides execution) {
        String role = execution
                .role()
                .map(value -> value.id() + " · 版本 " + value.revision())
                .orElse("沿用工作空间配置");
        String provider = execution
                .provider()
                .map(value -> value.endpointId() + "/" + value.model() + " · 版本 " + value.endpointRevision())
                .orElse("沿用工作空间配置");
        String permission = execution
                .permissionProfile()
                .map(value -> value.id() + " · 版本 " + value.version())
                .orElse("沿用工作空间配置");
        return "角色：" + role + "\n\n模型：" + provider + "\n\n权限：" + permission
                + "\n\n仅恢复目录中精确匹配的版本。保存的引用失效或尚未加载时保持未选，请检查目录并明确选择。";
    }
}
