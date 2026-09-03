package com.javaclaw.desktop.view;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/**
 * 解析 ViewAction 的权威 command binding，并跟踪页面内的单行选择。
 *
 * <p>本类型只在 JavaFX Thread 中使用。绑定优先读取 source values；仅当字段不存在时，才读取同一数据源由 SINGLE 列表或表格产生的选中行。缺值时始终 fail closed，不猜测首行或其他数据源。
 */
final class ViewCommandBindingResolver {
    private final ViewData data;
    private final Map<String, String> selectableKeys;
    private final Map<String, Map<String, Object>> selectedRows = new HashMap<>();
    private final List<Runnable> observers = new ArrayList<>();

    ViewCommandBindingResolver(ViewSchema schema, ViewData data) {
        this.data = Objects.requireNonNull(data, "data");
        selectableKeys = selectableKeys(Objects.requireNonNull(schema, "schema"));
        selectableKeys.forEach(this::initializeSelection);
    }

    void select(String sourceId, Map<String, Object> row) {
        if (!selectableKeys.containsKey(sourceId)) {
            return;
        }
        Map<String, Object> previous;
        if (row == null) {
            previous = selectedRows.remove(sourceId);
        } else {
            previous = selectedRows.put(sourceId, Map.copyOf(row));
        }
        if (!Objects.equals(previous, selectedRows.get(sourceId))) {
            observers.forEach(Runnable::run);
        }
    }

    void observe(Runnable observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    Optional<String> unavailable(ViewAction action, Map<String, Object> row) {
        for (String field : action.rowArguments().values()) {
            if (missing(row.get(field))) {
                return Optional.of("请选择包含字段 “" + field + "” 的记录");
            }
        }
        if (action.expectedRevision() instanceof ExpectedRevisionBinding.RowField field
                && missing(row.get(field.field()))) {
            return Optional.of("请选择包含 revision 字段 “" + field.field() + "” 的记录");
        }
        return action.commandBindings().stream()
                .filter(binding -> value(binding.binding()).isEmpty())
                .map(this::missingMessage)
                .findFirst();
    }

    ViewCommandInvocation invocation(
            ViewAction action, Map<String, Object> userArguments, Map<String, Object> selectedRow) {
        Optional<String> failure = unavailable(action, selectedRow);
        if (failure.isPresent()) {
            throw new IllegalArgumentException(failure.orElseThrow());
        }
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>(userArguments);
        action.arguments().forEach(arguments::put);
        action.rowArguments().forEach((argument, field) -> arguments.put(argument, selectedRow.get(field)));
        action.commandBindings()
                .forEach(binding -> arguments.put(
                        binding.argumentName(), value(binding.binding()).orElseThrow()));
        return new ViewCommandInvocation(
                action.command(), Map.copyOf(arguments), expectedRevision(action, selectedRow), action.dangerous());
    }

    private Optional<Object> value(ViewBinding binding) {
        ViewData.Source source = data.source(binding.sourceId());
        if (source.values().containsKey(binding.field())) {
            return present(source.values().get(binding.field()));
        }
        Map<String, Object> row = selectedRows.get(binding.sourceId());
        if (row == null || !row.containsKey(binding.field())) {
            return Optional.empty();
        }
        return present(row.get(binding.field()));
    }

    private Optional<Object> present(Object value) {
        return missing(value) ? Optional.empty() : Optional.of(value);
    }

    private boolean missing(Object value) {
        return value == null
                || value instanceof CharSequence text && text.toString().isBlank();
    }

    private String missingMessage(ViewCommandBinding binding) {
        return "操作缺少权威字段 “" + binding.binding().sourceId() + "."
                + binding.binding().field() + "”";
    }

    private long expectedRevision(ViewAction action, Map<String, Object> selectedRow) {
        return switch (action.expectedRevision()) {
            case ExpectedRevisionBinding.None ignored -> 0;
            case ExpectedRevisionBinding.SourceRevision source ->
                data.source(source.sourceId()).revision();
            case ExpectedRevisionBinding.RowField field -> revision(selectedRow.get(field.field()));
        };
    }

    private long revision(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("选中行 revision 必须是非负整数");
        }
        try {
            long revision = new BigDecimal(number.toString()).longValueExact();
            if (revision < 0) {
                throw new IllegalArgumentException("选中行 revision 必须是非负整数");
            }
            return revision;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException("选中行 revision 必须是非负整数", failure);
        }
    }

    private void initializeSelection(String sourceId, String keyField) {
        ViewData.Source source = data.source(sourceId);
        source.selectedKey()
                .flatMap(selected -> source.rows().stream()
                        .filter(row -> selected.equals(Objects.toString(row.get(keyField), "")))
                        .findFirst())
                .ifPresent(row -> selectedRows.put(sourceId, row));
    }

    private Map<String, String> selectableKeys(ViewSchema schema) {
        Map<String, String> keys = new HashMap<>();
        for (ViewSchema.Node node : schema.nodes()) {
            switch (node) {
                case ViewSchema.ListView list ->
                    addSelectable(keys, list.sourceId(), list.keyField(), list.selection());
                case ViewSchema.Table table ->
                    addSelectable(keys, table.sourceId(), table.keyField(), table.selection());
                default -> {
                    // 只有单选列表和表格能够提供权威选中行。
                }
            }
        }
        return Map.copyOf(keys);
    }

    private void addSelectable(
            Map<String, String> keys, String sourceId, String keyField, ViewSelectionMode selection) {
        if (selection != ViewSelectionMode.SINGLE) {
            return;
        }
        String existing = keys.putIfAbsent(sourceId, keyField);
        if (existing != null && !existing.equals(keyField)) {
            throw new IllegalArgumentException("同一数据源的单选控件必须使用相同 keyField: " + sourceId);
        }
    }
}
