package com.javaclaw.desktop.view;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewStructuredListValidationTest {
    @Test
    void 接受边界内且类型完整的结构化行() {
        ViewStructuredListField definition = definition(
                0,
                2,
                List.of(
                        field("title", "标题", ViewStructuredItemType.TEXT, bounds(1, 5)),
                        field("enabled", "启用", ViewStructuredItemType.BOOLEAN, optional()),
                        choice(),
                        number(),
                        tags()));
        Map<String, Object> row = row("one");
        row.put("title", "任务");
        row.put("enabled", true);
        row.put("kind", "turn");
        row.put("weight", new BigDecimal("2"));
        row.put("tags", List.of("safe", "reviewed"));

        assertTrue(
                ViewStructuredListValidation.validate(definition, List.of(row)).isEmpty());
        assertTrue(ViewStructuredListValidation.validate(definition, List.of()).isEmpty());
    }

    @Test
    void 拒绝行数越界和重复稳定键() {
        ViewStructuredListField definition =
                definition(1, 2, List.of(field("title", "标题", ViewStructuredItemType.TEXT, required())));
        Map<String, Object> first = textRow("same", "第一步");
        Map<String, Object> second = textRow("same", "第二步");

        assertEquals("步骤行数不符合限制", failure(definition, List.of()));
        assertEquals("步骤行数不符合限制", failure(definition, List.of(first, second, textRow("third", "第三步"))));
        assertEquals("步骤包含重复行键", failure(definition, List.of(first, second)));
    }

    @Test
    void 文本校验区分必填最短自定义上限和平台上限() {
        ViewStructuredListField definition =
                definition(1, 1, List.of(field("title", "标题", ViewStructuredItemType.TEXT, bounds(2, 4))));

        assertEquals("标题不能为空", failure(definition, List.of(textRow("one", " "))));
        assertEquals("标题长度不足", failure(definition, List.of(textRow("one", "a"))));
        assertEquals("标题长度超出限制", failure(definition, List.of(textRow("one", "abcde"))));

        ViewStructuredListField hardLimit =
                definition(1, 1, List.of(field("title", "标题", ViewStructuredItemType.MULTILINE, optional())));
        assertEquals(
                "标题长度超出限制",
                failure(hardLimit, List.of(textRow("one", "x".repeat(ViewStructuredListField.MAX_TEXT_LENGTH + 1)))));

        ViewStructuredListField optional =
                definition(1, 1, List.of(field("title", "标题", ViewStructuredItemType.TEXT, optional())));
        assertTrue(ViewStructuredListValidation.validate(optional, List.of(textRow("one", "")))
                .isEmpty());
    }

    @Test
    void 数值校验拒绝错误类型以及上下界之外的值() {
        ViewStructuredListField definition = definition(1, 1, List.of(number()));

        assertEquals("权重不能为空", failure(definition, List.of(valueRow("one", "weight", null))));
        assertEquals("权重必须是数值", failure(definition, List.of(valueRow("one", "weight", "2"))));
        assertEquals("权重小于允许的最小值", failure(definition, List.of(valueRow("one", "weight", new BigDecimal("-1")))));
        assertEquals("权重大于允许的最大值", failure(definition, List.of(valueRow("one", "weight", new BigDecimal("11")))));
        assertTrue(ViewStructuredListValidation.validate(definition, List.of(valueRow("one", "weight", BigDecimal.TEN)))
                .isEmpty());
    }

    @Test
    void 文本列表校验拒绝错误类型数量越界和过长条目() {
        ViewStructuredListField definition = definition(1, 1, List.of(tags()));

        assertEquals("标签不能为空", failure(definition, List.of(valueRow("one", "tags", List.of()))));
        assertEquals("标签条目数量或类型不符合限制", failure(definition, List.of(valueRow("one", "tags", "safe"))));
        assertEquals("标签条目数量或类型不符合限制", failure(definition, List.of(valueRow("one", "tags", List.of("safe", 1)))));
        assertEquals("标签条目数量不足", failure(definition, List.of(valueRow("one", "tags", List.of("one")))));
        assertEquals(
                "标签条目数量或类型不符合限制",
                failure(definition, List.of(valueRow("one", "tags", List.of("one", "two", "three", "four")))));
        assertEquals(
                "标签包含过长文本",
                failure(
                        definition,
                        List.of(valueRow(
                                "one",
                                "tags",
                                List.of("safe", "x".repeat(ViewStructuredListField.MAX_TEXT_LENGTH + 1))))));
        assertTrue(ViewStructuredListValidation.validate(
                        definition, List.of(valueRow("one", "tags", List.of("one", "two"))))
                .isEmpty());
    }

    @Test
    void 总文本量上限计入行内字符串与字符串列表() {
        ViewStructuredListField definition =
                definition(1, 1, List.of(field("title", "标题", ViewStructuredItemType.TEXT, optional())));
        Map<String, Object> row = textRow("one", "ok");
        row.put("external", List.of("x".repeat(ViewStructuredListField.MAX_TOTAL_TEXT_LENGTH)));
        row.put("ignored", 1);

        assertEquals("步骤文本总量超出限制", failure(definition, List.of(row)));
    }

    private static String failure(ViewStructuredListField definition, List<Map<String, Object>> rows) {
        return ViewStructuredListValidation.validate(definition, rows).orElseThrow();
    }

    private static ViewStructuredListField definition(int minRows, int maxRows, List<ViewStructuredItemField> fields) {
        return new ViewStructuredListField(
                "steps",
                "步骤",
                new ViewBinding("editor", "steps"),
                minRows,
                maxRows,
                "stepId",
                fields,
                initialRows(minRows, fields),
                Optional.empty());
    }

    private static List<Map<String, Object>> initialRows(int minRows, List<ViewStructuredItemField> fields) {
        if (minRows == 0) {
            return List.of();
        }
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("stepId", "initial");
        for (ViewStructuredItemField field : fields) {
            row.put(field.name(), initialValue(field));
        }
        return List.of(row);
    }

    private static Object initialValue(ViewStructuredItemField field) {
        return switch (field.type()) {
            case BOOLEAN -> false;
            case NUMBER -> BigDecimal.ZERO;
            case CHOICE -> "turn";
            case TEXT_LIST -> List.of("one", "two");
            case MULTILINE, TEXT -> field.validation().required() ? "ok" : "";
        };
    }

    private static ViewStructuredItemField field(
            String name, String label, ViewStructuredItemType type, ViewStructuredItemValidation validation) {
        return new ViewStructuredItemField(name, label, type, Optional.empty(), List.of(), validation, List.of());
    }

    private static ViewStructuredItemField choice() {
        return new ViewStructuredItemField(
                "kind",
                "类型",
                ViewStructuredItemType.CHOICE,
                Optional.of("turn"),
                List.of(),
                required(),
                List.of(new ViewOption("turn", "Turn"), new ViewOption("tool", "Tool")));
    }

    private static ViewStructuredItemField number() {
        return new ViewStructuredItemField(
                "weight",
                "权重",
                ViewStructuredItemType.NUMBER,
                Optional.of("0"),
                List.of(),
                new ViewStructuredItemValidation(
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(BigDecimal.ZERO),
                        Optional.of(BigDecimal.TEN),
                        Optional.empty(),
                        Optional.empty()),
                List.of());
    }

    private static ViewStructuredItemField tags() {
        return new ViewStructuredItemField(
                "tags",
                "标签",
                ViewStructuredItemType.TEXT_LIST,
                Optional.empty(),
                List.of("one", "two"),
                new ViewStructuredItemValidation(
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(2),
                        Optional.of(3)),
                List.of());
    }

    private static ViewStructuredItemValidation required() {
        return ViewStructuredItemValidation.required(true);
    }

    private static ViewStructuredItemValidation optional() {
        return ViewStructuredItemValidation.required(false);
    }

    private static ViewStructuredItemValidation bounds(int minimum, int maximum) {
        return new ViewStructuredItemValidation(
                true,
                Optional.of(minimum),
                Optional.of(maximum),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static Map<String, Object> textRow(String id, String value) {
        return valueRow(id, "title", value);
    }

    private static Map<String, Object> valueRow(String id, String name, Object value) {
        Map<String, Object> row = row(id);
        row.put(name, value);
        return row;
    }

    private static Map<String, Object> row(String id) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("stepId", id);
        return row;
    }
}
