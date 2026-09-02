package com.javaclaw.desktop.view;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFormField;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** 在创建 JavaFX 控件前限制 ViewSchema v2 的规模、引用和可调用 operation。 */
public final class ViewSchemaPolicy {
    private static final int MAX_NODES = 32;
    private static final int MAX_CHILDREN = 32;
    private static final int MAX_TEXT = 4_000;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern OPERATION =
            Pattern.compile("(?=.{1,128}$)[a-z][a-z0-9._-]{0,63}(?:/[a-z][a-z0-9._-]{0,63}){0,3}");

    private ViewSchemaPolicy() {}

    /**
     * 校验平台可安全渲染的全部边界。
     *
     * @param schema 服务端返回的页面
     * @return 原不可变 schema
     */
    public static ViewSchema requireSupported(ViewSchema schema) {
        Objects.requireNonNull(schema, "schema");
        if (schema.schemaVersion() != ViewSchema.CURRENT_VERSION) {
            throw new IllegalArgumentException("不支持的 ViewSchema 版本: " + schema.schemaVersion());
        }
        requireMaximum(schema.dataSources().size(), "数据源");
        if (schema.nodes().size() > MAX_NODES) {
            throw new IllegalArgumentException("顶层节点过多");
        }
        Set<String> sources = validateSources(schema.dataSources());
        Set<String> nodeIds = new HashSet<>();
        for (ViewSchema.Node node : schema.nodes()) {
            requireIdentifier(node.id(), "节点 ID");
            if (!nodeIds.add(node.id())) {
                throw new IllegalArgumentException("ViewSchema 节点 ID 重复: " + node.id());
            }
            validateNode(node, sources);
        }
        validateMasterSelections(schema);
        return schema;
    }

    private static Set<String> validateSources(java.util.List<ViewDataSource> dataSources) {
        Set<String> sources = new HashSet<>();
        for (ViewDataSource source : dataSources) {
            requireIdentifier(source.id(), "数据源 ID");
            requireOperation(source.query());
            validateArgumentBindings(source, sources);
            if (!sources.add(source.id())) {
                throw new IllegalArgumentException("ViewSchema 数据源 ID 重复: " + source.id());
            }
            validateArguments(source.arguments(), "数据源参数");
        }
        return Set.copyOf(sources);
    }

    private static void validateArgumentBindings(ViewDataSource source, Set<String> precedingSources) {
        requireMaximum(source.argumentBindings().size(), "数据源动态参数");
        Set<String> arguments = new HashSet<>(source.arguments().keySet());
        for (ViewArgumentBinding binding : source.argumentBindings()) {
            requireField(binding.argument());
            requireIdentifier(binding.sourceId(), "主数据源 ID");
            requireField(binding.rowField());
            if (!precedingSources.contains(binding.sourceId())) {
                throw new IllegalArgumentException("动态参数只能引用此前声明的主数据源: " + binding.sourceId());
            }
            if (!arguments.add(binding.argument())) {
                throw new IllegalArgumentException("数据源参数重复: " + binding.argument());
            }
        }
    }

    private static void validateMasterSelections(ViewSchema schema) {
        Set<String> selectable = new HashSet<>();
        for (ViewSchema.Node node : schema.nodes()) {
            switch (node) {
                case ViewSchema.ListView list -> addSelectable(selectable, list.sourceId(), list.selection());
                case ViewSchema.Table table -> addSelectable(selectable, table.sourceId(), table.selection());
                default -> {
                    // 其他节点不产生行选择。
                }
            }
        }
        schema.dataSources().stream()
                .flatMap(source -> source.argumentBindings().stream())
                .filter(binding -> !selectable.contains(binding.sourceId()))
                .findFirst()
                .ifPresent(binding -> {
                    throw new IllegalArgumentException("动态参数主数据源必须由单选列表或表格展示: " + binding.sourceId());
                });
    }

    private static void addSelectable(Set<String> selectable, String sourceId, ViewSelectionMode selection) {
        if (selection == ViewSelectionMode.SINGLE) {
            selectable.add(sourceId);
        }
    }

    private static void validateNode(ViewSchema.Node node, Set<String> sources) {
        switch (node) {
            case ViewSchema.Form form -> validateForm(form, sources);
            case ViewSchema.ListView list -> {
                requireSource(list.sourceId(), sources);
                requireField(list.keyField());
                requireField(list.titleField());
                requireField(list.detailField());
                validateRowActions(list.actions(), list.selection(), sources);
            }
            case ViewSchema.Table table -> {
                requireSource(table.sourceId(), sources);
                requireField(table.keyField());
                requireMaximum(table.columns().size(), "表格列");
                table.columns().forEach(column -> requireField(column.field()));
                validateRowActions(table.actions(), table.selection(), sources);
            }
            case ViewSchema.Card card -> {
                requireText(card.title());
                requireText(card.body());
                validateActions(card.actions(), sources, false);
            }
            case ViewSchema.Progress progress -> requireSource(progress.sourceId(), sources);
            case ViewSchema.Timeline timeline -> requireSource(timeline.sourceId(), sources);
            case ViewSchema.Markdown markdown -> requireBinding(markdown.source(), sources);
            case ViewSchema.Code code -> {
                requireBinding(code.source(), sources);
                code.language().ifPresent(binding -> requireBinding(binding, sources));
            }
            case ViewSchema.Artifact artifact -> {
                requireBinding(artifact.reference(), sources);
                requireBinding(artifact.mediaType(), sources);
            }
            case ViewSchema.Graph graph -> {
                requireSource(graph.nodeSourceId(), sources);
                requireSource(graph.edgeSourceId(), sources);
                requireField(graph.nodeIdField());
                requireField(graph.nodeLabelField());
                requireField(graph.nodeKindField());
                requireField(graph.edgeFromField());
                requireField(graph.edgeToField());
            }
        }
    }

    private static void validateForm(ViewSchema.Form form, Set<String> sources) {
        requireMaximum(form.fields().size(), "表单字段");
        Set<String> names = new HashSet<>();
        Set<ViewBinding> bindings = new HashSet<>();
        for (ViewFormField field : form.fields()) {
            requireField(field.name());
            if (!names.add(field.name()) || !bindings.add(field.binding())) {
                throw new IllegalArgumentException("表单字段名或绑定重复: " + field.name());
            }
            requireBinding(field.binding(), sources);
            field.visibleWhen().ifPresent(condition -> requireBinding(condition.binding(), sources));
            switch (field) {
                case ViewField scalar -> validateScalarField(scalar, sources);
                case ViewStructuredListField structured -> validateStructuredField(structured);
            }
        }
        validateAction(form.submit(), sources, false);
        form.submit().commandBindings().stream()
                .map(ViewCommandBinding::argumentName)
                .filter(names::contains)
                .findFirst()
                .ifPresent(argument -> {
                    throw new IllegalArgumentException("表单字段不能覆盖权威操作参数: " + argument);
                });
        if (!form.submit().rowArguments().isEmpty()) {
            throw new IllegalArgumentException("表单操作不能声明行参数");
        }
    }

    private static void validateScalarField(ViewField field, Set<String> sources) {
        requireText(field.label());
        field.optionSource().ifPresent(option -> requireSource(option.sourceId(), sources));
        requireMaximum(field.options().size(), "表单选项");
        field.options().forEach(option -> {
            requireText(option.value());
            requireText(option.label());
        });
        field.validation()
                .attachment()
                .ifPresent(policy -> requireMaximum(policy.acceptedMediaTypes().size(), "Attachment 媒体类型"));
    }

    private static void validateStructuredField(ViewStructuredListField field) {
        requireText(field.label());
        requireField(field.itemKey());
        if (field.minRows() < 0
                || field.maxRows() > ViewStructuredListField.MAX_ROWS
                || field.minRows() > field.maxRows()) {
            throw new IllegalArgumentException("结构化列表行数限制无效");
        }
        if (field.itemFields().size() > ViewStructuredListField.MAX_ITEM_FIELDS) {
            throw new IllegalArgumentException("结构化列表字段过多");
        }
        if ((long) field.itemFields().size() * field.maxRows() > ViewStructuredListField.MAX_RENDERED_INPUTS) {
            throw new IllegalArgumentException("结构化列表渲染节点过多");
        }
        Set<String> names = new HashSet<>();
        names.add(field.itemKey());
        for (ViewStructuredItemField item : field.itemFields()) {
            validateStructuredItem(item, names);
        }
    }

    private static void validateStructuredItem(ViewStructuredItemField field, Set<String> names) {
        requireField(field.name());
        requireText(field.label());
        if (!names.add(field.name())) {
            throw new IllegalArgumentException("结构化列表字段重复: " + field.name());
        }
        requireMaximum(field.options().size(), "结构化列表选项");
        field.options().forEach(option -> {
            requireText(option.value());
            requireText(option.label());
        });
        field.initialValue().ifPresent(ViewSchemaPolicy::requireText);
        field.initialTextList().forEach(ViewSchemaPolicy::requireText);
    }

    private static void validateActions(java.util.List<ViewAction> actions, Set<String> sources, boolean allowRows) {
        requireMaximum(actions.size(), "操作");
        actions.forEach(action -> validateAction(action, sources, allowRows));
    }

    private static void validateRowActions(
            java.util.List<ViewAction> actions, ViewSelectionMode selection, Set<String> sources) {
        validateActions(actions, sources, true);
        if (selection == ViewSelectionMode.NONE
                && actions.stream().anyMatch(action -> !action.rowArguments().isEmpty())) {
            throw new IllegalArgumentException("未启用行选择时不能声明行参数");
        }
    }

    private static void validateAction(ViewAction action, Set<String> sources, boolean allowRows) {
        requireOperation(action.command());
        validateArguments(action.arguments(), "操作参数");
        validateRowArguments(action.rowArguments());
        action.commandBindings().forEach(binding -> {
            requireField(binding.argumentName());
            requireBinding(binding.binding(), sources);
        });
        if (!allowRows && !action.rowArguments().isEmpty()) {
            throw new IllegalArgumentException("该节点不能声明行参数");
        }
        switch (action.expectedRevision()) {
            case ExpectedRevisionBinding.None ignored -> {
                // 创建命令不需要资源版本。
            }
            case ExpectedRevisionBinding.SourceRevision source -> requireSource(source.sourceId(), sources);
            case ExpectedRevisionBinding.RowField row -> {
                requireField(row.field());
                if (!allowRows) {
                    throw new IllegalArgumentException("该节点不能从行字段读取 expected revision");
                }
            }
        }
    }

    private static void validateArguments(Map<String, String> arguments, String label) {
        requireMaximum(arguments.size(), label);
        arguments.forEach((key, value) -> {
            requireField(key);
            requireText(Objects.requireNonNull(value, "argument value"));
        });
    }

    private static void validateRowArguments(Map<String, String> arguments) {
        requireMaximum(arguments.size(), "行参数");
        arguments.forEach((argument, field) -> {
            requireField(argument);
            requireField(field);
        });
    }

    private static void requireBinding(ViewBinding binding, Set<String> sources) {
        requireSource(binding.sourceId(), sources);
        requireField(binding.field());
    }

    private static void requireSource(String sourceId, Set<String> sources) {
        if (!sources.contains(sourceId)) {
            throw new IllegalArgumentException("ViewSchema 引用了未声明的数据源: " + sourceId);
        }
    }

    private static void requireIdentifier(String value, String label) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " 不安全: " + value);
        }
    }

    private static void requireField(String field) {
        if (!FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("ViewSchema 字段名不安全: " + field);
        }
    }

    private static void requireOperation(String operation) {
        if (!OPERATION.matcher(operation).matches()) {
            throw new IllegalArgumentException("ViewSchema 操作名不安全: " + operation);
        }
    }

    private static void requireMaximum(int size, String label) {
        if (size > MAX_CHILDREN) {
            throw new IllegalArgumentException(label + "过多");
        }
    }

    private static void requireText(String value) {
        if (value.length() > MAX_TEXT) {
            throw new IllegalArgumentException("ViewSchema 静态文本过长");
        }
    }
}
