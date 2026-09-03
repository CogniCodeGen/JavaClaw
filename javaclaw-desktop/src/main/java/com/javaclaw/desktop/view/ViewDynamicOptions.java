package com.javaclaw.desktop.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewOptionSource;

/** 解析 ViewSchema 动态选项，并执行受限的同表单或同行等值过滤。 */
final class ViewDynamicOptions {
    private ViewDynamicOptions() {}

    static List<ViewOption> resolve(
            List<ViewOption> declared, ViewOptionSource source, ViewData data, Function<String, Object> inputValue) {
        LinkedHashMap<String, ViewOption> options = new LinkedHashMap<>();
        declared.forEach(option -> options.put(option.value(), option));
        Object expected = source.filter()
                .map(filter -> inputValue.apply(filter.inputField()))
                .orElse(null);
        for (Map<String, Object> row : data.source(source.sourceId()).rows()) {
            if (source.filter().isPresent()
                    && !Objects.equals(
                            Objects.toString(
                                    row.get(source.filter().orElseThrow().sourceField()), ""),
                            Objects.toString(expected, ""))) {
                continue;
            }
            ViewOption option = new ViewOption(
                    Objects.toString(row.get(source.valueField()), ""),
                    Objects.toString(row.get(source.labelField()), ""));
            options.putIfAbsent(option.value(), option);
        }
        return List.copyOf(options.values());
    }
}
