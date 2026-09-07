package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** 自动化页面共享的独立模型和权限选择控件，目录由 App Server 权威端口提供。 */
final class AutomationSelectionView {
    static final String PROVIDERS = "execution/provider/view.list";
    static final String PERMISSIONS = "execution/permission/view.list";

    private AutomationSelectionView() {}

    static List<ViewDataSource> withSources(List<ViewDataSource> existing) {
        List<ViewDataSource> combined = new ArrayList<>(existing);
        combined.addAll(sources());
        return List.copyOf(combined);
    }

    static List<ViewSchema.Node> withTables(List<ViewSchema.Node> existing) {
        List<ViewSchema.Node> combined = new ArrayList<>();
        boolean inserted = false;
        for (ViewSchema.Node node : existing) {
            if (!inserted && node instanceof ViewSchema.Form) {
                combined.addAll(tables());
                inserted = true;
            }
            combined.add(node);
        }
        return List.copyOf(combined);
    }

    static List<ViewField> withFields(String source, List<ViewField> existing) {
        List<ViewField> combined = new ArrayList<>(fields(source));
        combined.addAll(existing);
        return List.copyOf(combined);
    }

    static List<ViewDataSource> sources() {
        return List.of(
                new ViewDataSource("providers", PROVIDERS, Map.of(), List.of(), 100),
                new ViewDataSource("permissions", PERMISSIONS, Map.of(), List.of(), 100));
    }

    static List<ViewSchema.Node> tables() {
        return List.of(table("providers", "选择模型"), table("permissions", "选择权限"));
    }

    static List<ViewCommandBinding> bindings() {
        return List.of(
                new ViewCommandBinding("provider", new ViewBinding("providers", "provider")),
                new ViewCommandBinding("permissionProfile", new ViewBinding("permissions", "permissionProfile")));
    }

    static List<ViewField> fields(String source) {
        return List.of(
                choice(
                        source,
                        "approvalPolicy",
                        "审批要求",
                        "RISKY",
                        List.of(
                                new ViewOption("NONE", "沿用权限要求"),
                                new ViewOption("RISKY", "有风险时审批"),
                                new ViewOption("EVERY_CALL", "每次工具调用审批"))),
                choice(
                        source,
                        "reasoning",
                        "推理强度",
                        "MEDIUM",
                        Arrays.stream(ReasoningPreference.values())
                                .map(value -> new ViewOption(value.name(), value.name()))
                                .toList()));
    }

    static ExtensionResponse query(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionPayloadCodec codec) {
        boolean providers = PROVIDERS.equals(request.operation());
        String source = providers ? "providers" : "permissions";
        ViewQueryRequest query = codec.decode(request.payload(), ViewQueryRequest.class);
        if (!source.equals(query.dataSourceId()) || !query.arguments().isEmpty()) {
            throw new IllegalArgumentException("execution selection catalog does not accept arguments");
        }
        List<Map<String, Object>> rows = providers ? providerRows(context) : permissionRows(context);
        int start = pageStart(rows, query.cursor());
        int end = Math.min(rows.size(), start + query.limit());
        List<Map<String, Object>> page = rows.subList(start, end);
        boolean more = end < rows.size();
        ViewQueryResult result = new ViewQueryResult(
                source,
                page.stream().map(codec::encode).toList(),
                codec.encode(Map.of()),
                more ? page.getLast().get("id").toString() : "",
                more,
                rows.stream()
                        .mapToLong(row -> ((Number) row.get("revision")).longValue())
                        .max()
                        .orElse(0));
        return new ExtensionResponse(codec.encode(result), result.revision());
    }

    private static List<Map<String, Object>> providerRows(ExtensionExecutionContext context) {
        List<Map<String, Object>> rows = new ArrayList<>();
        context.executionPolicies()
                .providers(context.workspaceId())
                .forEach(provider -> provider.spec().models().stream()
                        .filter(model -> model.purposes().contains(ProviderModelPurpose.CHAT))
                        .forEach(model -> {
                            ProviderRef reference =
                                    new ProviderRef(provider.id(), provider.revision(), model.modelId());
                            rows.add(Map.of(
                                    "id",
                                    provider.id() + "/" + model.modelId(),
                                    "name",
                                    provider.spec().displayName() + " / " + model.displayName(),
                                    "revision",
                                    provider.revision(),
                                    "provider",
                                    reference));
                        }));
        return List.copyOf(rows);
    }

    private static List<Map<String, Object>> permissionRows(ExtensionExecutionContext context) {
        return context.executionPolicies().permissions(context.workspaceId()).stream()
                .map(permission -> Map.<String, Object>of(
                        "id",
                        permission.id(),
                        "name",
                        permission.id(),
                        "revision",
                        permission.version(),
                        "permissionProfile",
                        new PermissionProfileRef(permission.id(), permission.version())))
                .toList();
    }

    private static int pageStart(List<Map<String, Object>> rows, String cursor) {
        if (cursor.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < rows.size(); index++) {
            if (cursor.equals(rows.get(index).get("id"))) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("execution selection catalog cursor is stale");
    }

    private static ViewSchema.Table table(String source, String title) {
        return new ViewSchema.Table(
                source + "Selection",
                title,
                source,
                "id",
                List.of(
                        new ViewSchema.Column("name", title, Optional.of(320)),
                        new ViewSchema.Column("revision", "版本", Optional.of(90))),
                ViewSelectionMode.SINGLE,
                List.of());
    }

    private static ViewField choice(
            String source, String name, String title, String initial, List<ViewOption> options) {
        return new ViewField(
                name,
                title,
                ViewFieldType.CHOICE,
                new ViewBinding(source, name),
                Optional.of(initial),
                ViewFieldValidation.required(true),
                options,
                Optional.empty(),
                Optional.empty());
    }
}
