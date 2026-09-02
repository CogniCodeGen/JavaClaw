package com.javaclaw.extension.spi;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;

/**
 * Schedule 可选择目标的权威目录端口。
 *
 * <p>目录只返回当前 Workspace 中仍可用于新 Schedule 的精确版本。保存 Definition 时必须再次调用 {@code requireDefinition} 或
 * {@code requireAction}，不能信任此前展示给客户端的行。
 */
public interface ScheduleTargetCatalogPort {
    /** SchedulableAction 固定参数允许的标量类型。 */
    enum ScalarType {
        /** UTF-8 文本。 */
        STRING,
        /** 有限十进制数。 */
        NUMBER,
        /** 布尔值。 */
        BOOLEAN
    }

    /**
     * 可调度 Definition 的扩展内条目。
     *
     * @param definitionId Definition 稳定标识
     * @param revision 当前精确 revision
     * @param displayName 用户可见名称
     */
    record DefinitionEntry(String definitionId, long revision, String displayName) {
        /** 校验条目字段。 */
        public DefinitionEntry {
            definitionId = text(definitionId, "definitionId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            displayName = text(displayName, "displayName");
        }
    }

    /**
     * 带扩展所有者的可调度 Definition 选项。
     *
     * @param extensionId Definition 所属扩展
     * @param definitionId Definition 稳定标识
     * @param revision 当前精确 revision
     * @param displayName 用户可见名称
     */
    record DefinitionOption(String extensionId, String definitionId, long revision, String displayName) {
        /** 校验选项字段。 */
        public DefinitionOption {
            extensionId = text(extensionId, "extensionId");
            definitionId = text(definitionId, "definitionId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            displayName = text(displayName, "displayName");
        }
    }

    /**
     * SchedulableAction 的一个固定参数字段。
     *
     * @param name 参数名
     * @param label 用户可见标签
     * @param type 标量类型
     * @param required 是否必填
     */
    record ActionField(String name, String label, ScalarType type, boolean required) {
        /** 校验字段描述。 */
        public ActionField {
            name = actionFieldName(name);
            label = text(label, "label");
            if (label.length() > 240) {
                throw new IllegalArgumentException("action field label is too long");
            }
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * 可供新 Schedule 选择的 SchedulableAction。
     *
     * @param extensionId Action 所属扩展
     * @param operation 已声明为可调度的 command operation
     * @param displayName 用户可见名称
     * @param fields 允许写入固定 payload 的完整标量字段
     * @param expectedRevision 命令目标精确 revision；无版本目标为 0
     */
    record ActionOption(
            String extensionId, String operation, String displayName, List<ActionField> fields, long expectedRevision) {
        /** 校验 Action 描述，禁止同名固定参数。 */
        public ActionOption {
            extensionId = text(extensionId, "extensionId");
            operation = text(operation, "operation");
            displayName = text(displayName, "displayName");
            fields = List.copyOf(fields);
            if (fields.size() > 32
                    || fields.stream().map(ActionField::name).distinct().count() != fields.size()) {
                throw new IllegalArgumentException("action fields must be unique and not exceed 32");
            }
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must not be negative");
            }
        }

        /**
         * 生成平台允许的一层封闭 primitive object Schema。
         *
         * @return 字段按名称排序的稳定 JSON Schema
         */
        public CanonicalPayload inputSchema() {
            List<ActionField> ordered = fields.stream()
                    .sorted(Comparator.comparing(ActionField::name))
                    .toList();
            String properties = ordered.stream()
                    .map(field -> "\"" + field.name() + "\":{\"type\":\""
                            + field.type().name().toLowerCase(Locale.ROOT)
                            + "\"}")
                    .collect(java.util.stream.Collectors.joining(","));
            String required = ordered.stream()
                    .filter(ActionField::required)
                    .map(field -> "\"" + field.name() + "\"")
                    .collect(java.util.stream.Collectors.joining(","));
            return new CanonicalPayload("{\"additionalProperties\":false,\"properties\":{"
                    + properties
                    + "},\"required\":["
                    + required
                    + "],\"type\":\"object\"}");
        }

        /**
         * 返回字段 Schema 的稳定 SHA-256。
         *
         * @return 小写十六进制摘要
         */
        public String schemaHash() {
            return inputSchema().sha256();
        }
    }

    /**
     * 列出当前可用于新 Schedule 的 Definition 精确版本。
     *
     * @param workspaceId Workspace
     * @return 稳定排序的不可变目录
     * @throws Exception 扩展目录读取失败
     */
    List<DefinitionOption> definitions(WorkspaceId workspaceId) throws Exception;

    /**
     * 列出当前可用于新 Schedule 的显式 Action。
     *
     * @param workspaceId Workspace
     * @return 稳定排序的不可变目录
     * @throws Exception 扩展目录读取失败
     */
    List<ActionOption> actions(WorkspaceId workspaceId) throws Exception;

    /**
     * 保存前重新确认一个 Definition 精确版本仍在权威目录中。
     *
     * @param workspaceId Workspace
     * @param extensionId 扩展标识
     * @param definitionId Definition 标识
     * @param revision 精确 revision
     * @return 当前权威选项
     * @throws Exception 目录读取失败
     */
    default DefinitionOption requireDefinition(
            WorkspaceId workspaceId, String extensionId, String definitionId, long revision) throws Exception {
        return definitions(workspaceId).stream()
                .filter(option -> option.extensionId().equals(extensionId)
                        && option.definitionId().equals(definitionId)
                        && option.revision() == revision)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Schedulable Definition is unavailable or stale"));
    }

    /**
     * 保存前重新确认一个 Action 仍在权威目录中。
     *
     * @param workspaceId Workspace
     * @param extensionId 扩展标识
     * @param operation command operation
     * @param expectedRevision 精确 revision
     * @return 当前权威选项
     * @throws Exception 目录读取失败
     */
    default ActionOption requireAction(
            WorkspaceId workspaceId, String extensionId, String operation, long expectedRevision) throws Exception {
        return actions(workspaceId).stream()
                .filter(option -> option.extensionId().equals(extensionId)
                        && option.operation().equals(operation)
                        && option.expectedRevision() == expectedRevision)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("SchedulableAction is unavailable or stale"));
    }

    /**
     * 创建始终安全拒绝的未装配端口。
     *
     * @return fail-closed 端口
     */
    static ScheduleTargetCatalogPort unavailable() {
        return new ScheduleTargetCatalogPort() {
            @Override
            public List<DefinitionOption> definitions(WorkspaceId workspaceId) {
                Objects.requireNonNull(workspaceId, "workspaceId");
                throw new IllegalStateException("Schedule target catalog is unavailable");
            }

            @Override
            public List<ActionOption> actions(WorkspaceId workspaceId) {
                Objects.requireNonNull(workspaceId, "workspaceId");
                throw new IllegalStateException("Schedule target catalog is unavailable");
            }
        };
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String actionFieldName(String value) {
        String normalized = text(value, "name");
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("action field name is unsafe");
        }
        return normalized;
    }
}
