package com.javaclaw.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewPlatformDataSource;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 解析 ViewSchema v2 可引用的平台只读数据源。
 *
 * <p>工具候选严格来自固定 Workspace 的服务端解析出的 Agent Role 与独立 PermissionProfile 精确版本；服务端 {@code tool/search} 仍负责 latest
 * lifecycle、实时撤权和目录 revision 校验。未绑定或失效时直接失败，不退回全局工具目录。
 */
final class PlatformViewDataSourceResolver {
    private static final int TOOL_LIMIT = 100;
    private static final int MAXIMUM_POINTERS = 200;
    private static final int MAXIMUM_SCHEMA_DEPTH = 8;
    private static final Set<String> SCALAR_TYPES = Set.of("string", "number", "integer", "boolean");

    private final JavaClawClient client;
    private final WorkspaceId workspaceId;
    private final CanonicalJson json = new CanonicalJson();
    private ToolCatalogQueryResult catalog;

    PlatformViewDataSourceResolver(JavaClawClient client, WorkspaceId workspaceId) {
        this.client = Objects.requireNonNull(client, "client");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    Optional<ViewData.Source> resolve(ViewDataSource source) {
        Objects.requireNonNull(source, "source");
        if (!ViewPlatformDataSource.supports(source.query())) {
            return Optional.empty();
        }
        ToolCatalogQueryResult current = catalog();
        List<Map<String, Object>> rows = ViewPlatformDataSource.TOOL_CATALOG.equals(source.query())
                ? toolRows(current.tools(), source.pageSize())
                : pointerRows(current.tools(), Math.min(source.pageSize(), MAXIMUM_POINTERS));
        return Optional.of(new ViewData.Source(
                rows,
                Map.of("catalogRevision", current.catalogRevision()),
                "",
                "",
                false,
                current.catalogRevision(),
                0,
                Optional.empty()));
    }

    private ToolCatalogQueryResult catalog() {
        if (catalog != null) {
            return catalog;
        }
        PromptManifestPreview configuration =
                client.prompts().preview(workspaceId, Optional.empty(), ExecutionOverrides.empty());
        AgentRoleRef roleRef = configuration.role();
        AgentRole role = client.roles().read(roleRef.id(), roleRef.revision());
        if (role.lifecycle() != RoleLifecycle.ACTIVE) {
            throw new IllegalStateException("当前工作区默认 Agent 已失效，无法读取工具目录");
        }
        catalog = client.tools()
                .catalog(workspaceId, configuration.permissionProfile(), Optional.of(roleRef), "", TOOL_LIMIT);
        return catalog;
    }

    private List<Map<String, Object>> toolRows(List<ToolDescriptor> tools, int limit) {
        return tools.stream().limit(limit).map(this::toolRow).toList();
    }

    private Map<String, Object> toolRow(ToolDescriptor tool) {
        return Map.of(
                ViewPlatformDataSource.TOOL_NAME_FIELD,
                tool.identity().name(),
                ViewPlatformDataSource.TOOL_LABEL_FIELD,
                toolLabel(tool),
                "toolRevision",
                tool.identity().revision(),
                "schemaHash",
                tool.outputSchema().sha256());
    }

    private List<Map<String, Object>> pointerRows(List<ToolDescriptor> tools, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ToolDescriptor tool : tools) {
            for (SchemaPointer pointer : scalarPointers(tool)) {
                if (rows.size() == limit) {
                    return List.copyOf(rows);
                }
                rows.add(Map.of(
                        ViewPlatformDataSource.TOOL_NAME_FIELD,
                        tool.identity().name(),
                        ViewPlatformDataSource.POINTER_FIELD,
                        pointer.pointer(),
                        ViewPlatformDataSource.POINTER_LABEL_FIELD,
                        pointer.pointer() + " · " + pointer.type(),
                        "toolRevision",
                        tool.identity().revision(),
                        "schemaHash",
                        tool.outputSchema().sha256()));
            }
        }
        return List.copyOf(rows);
    }

    private List<SchemaPointer> scalarPointers(ToolDescriptor tool) {
        Map<?, ?> root = json.decode(tool.outputSchema(), Map.class);
        List<SchemaPointer> pointers = new ArrayList<>();
        collectProperties(root, "", 0, pointers);
        return List.copyOf(pointers);
    }

    private void collectProperties(Map<?, ?> schema, String parent, int depth, List<SchemaPointer> pointers) {
        if (depth >= MAXIMUM_SCHEMA_DEPTH || pointers.size() >= MAXIMUM_POINTERS) {
            return;
        }
        Object rawProperties = schema.get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties)) {
            return;
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Map<?, ?> child)) {
                continue;
            }
            String pointer = parent + "/" + escape(name);
            Object type = child.get("type");
            if (type instanceof String scalar && SCALAR_TYPES.contains(scalar)) {
                pointers.add(new SchemaPointer(pointer, scalar));
            } else if ("object".equals(type)) {
                collectProperties(child, pointer, depth + 1, pointers);
            }
            if (pointers.size() >= MAXIMUM_POINTERS) {
                return;
            }
        }
    }

    private String toolLabel(ToolDescriptor tool) {
        return tool.identity().name()
                + " · "
                + tool.identity().producerId()
                + " · r"
                + tool.identity().revision()
                + " · "
                + tool.outputSchema().sha256().substring(0, 12);
    }

    private String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private record SchemaPointer(String pointer, String type) {}
}
