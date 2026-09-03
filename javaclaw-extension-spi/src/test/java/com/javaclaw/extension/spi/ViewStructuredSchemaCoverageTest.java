package com.javaclaw.extension.spi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewStructuredSchemaCoverageTest {
    @Test
    void structuredList规范化全部标量类型并拒绝运行时类型注入() {
        ViewStructuredListField field = structured(0, 30, List.of());
        List<Map<String, Object>> normalized = field.normalizeRows(List.of(row("one")));

        assertEquals(field.initialRows(), field.normalizeRows(null));
        assertEquals(new BigDecimal("2.5"), normalized.getFirst().get("number"));
        assertEquals("task", normalized.getFirst().get("choice"));
        assertEquals(List.of("one", "two"), normalized.getFirst().get("tags"));
        assertEquals("", field.newItem("two").get("number"));
        assertThrows(IllegalArgumentException.class, () -> field.normalizeRows("array"));
        assertThrows(IllegalArgumentException.class, () -> field.newItem(" "));
        assertThrows(IllegalArgumentException.class, () -> field.newItem("x".repeat(129)));
        assertInvalidRow(field, "text", 1);
        assertInvalidRow(field, "number", "number");
        assertInvalidRow(field, "enabled", "true");
        assertInvalidRow(field, "choice", "missing");
        assertInvalidRow(field, "tags", List.of(1));
        assertInvalidRow(field, "tags", java.util.Collections.nCopies(33, "tag"));
        assertInvalidRow(field, "text", "x".repeat(ViewStructuredListField.MAX_TEXT_LENGTH + 1));
        assertThrows(IllegalArgumentException.class, () -> field.normalizeRows(List.of("row")));
        Map<Object, Object> nonStringKey = new LinkedHashMap<>();
        nonStringKey.put(1, "value");
        assertThrows(IllegalArgumentException.class, () -> field.normalizeRows(List.of(nonStringKey)));
    }

    @Test
    void structuredList拒绝错误行数范围和空字段() {
        ViewStructuredItemField text = textItem("text");

        assertInvalidList(-1, 1, "id", List.of(text));
        assertInvalidList(0, 101, "id", List.of(text));
        assertInvalidList(2, 1, "id", List.of(text));
        assertInvalidList(0, 1, "id", List.of());
    }

    @Test
    void structuredList拒绝重复字段和非法标识符() {
        ViewStructuredItemField text = textItem("text");

        assertInvalidList(0, 1, "id", List.of(text, text));
        assertInvalidList(0, 1, "text", List.of(text));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewStructuredListField(
                        "bad.name",
                        "条目",
                        new ViewBinding("editor", "items"),
                        0,
                        1,
                        "id",
                        List.of(text),
                        List.of(),
                        Optional.empty()));
    }

    @Test
    void structuredList限制全部行的总文本量() {
        List<Map<String, Object>> oversized = new ArrayList<>();
        for (int index = 0; index < 26; index++) {
            oversized.add(Map.of("id", "row-" + index, "text", "x".repeat(4_000)));
        }
        ViewStructuredListField bounded = new ViewStructuredListField(
                "items",
                "条目",
                new ViewBinding("editor", "items"),
                0,
                30,
                "id",
                List.of(textItem("text")),
                List.of(),
                Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> bounded.normalizeRows(oversized));
    }

    @Test
    void structuredItem拒绝非法名称校验种类和选项() {
        ViewOption option = new ViewOption("task", "任务");

        assertThrows(IllegalArgumentException.class, () -> textItem("bad.name"));
        assertThrows(
                IllegalArgumentException.class,
                () -> item(
                        "enabled",
                        ViewStructuredItemType.BOOLEAN,
                        Optional.empty(),
                        List.of(),
                        textValidation(),
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> item(
                        "choice", ViewStructuredItemType.CHOICE, Optional.empty(), List.of(), required(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> item(
                        "text",
                        ViewStructuredItemType.TEXT,
                        Optional.empty(),
                        List.of(),
                        textValidation(),
                        List.of(option)));
        assertThrows(
                IllegalArgumentException.class,
                () -> item(
                        "choice",
                        ViewStructuredItemType.CHOICE,
                        Optional.empty(),
                        List.of(),
                        required(),
                        List.of(option, option)));
    }

    @Test
    void structuredItem拒绝错误初值() {
        ViewOption option = new ViewOption("task", "任务");

        assertThrows(
                IllegalArgumentException.class,
                () -> item(
                        "enabled",
                        ViewStructuredItemType.BOOLEAN,
                        Optional.of("yes"),
                        List.of(),
                        required(),
                        List.of()));
        assertThrows(
                NumberFormatException.class,
                () -> item(
                        "number", ViewStructuredItemType.NUMBER, Optional.of("bad"), List.of(), required(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> item(
                        "choice",
                        ViewStructuredItemType.CHOICE,
                        Optional.of("missing"),
                        List.of(),
                        required(),
                        List.of(option)));
        assertThrows(
                IllegalArgumentException.class,
                () -> item(
                        "tags",
                        ViewStructuredItemType.TEXT_LIST,
                        Optional.of("bad"),
                        List.of(),
                        required(),
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> item(
                        "text",
                        ViewStructuredItemType.TEXT,
                        Optional.empty(),
                        List.of("bad"),
                        textValidation(),
                        List.of()));
    }

    @Test
    void dynamicChoice由平台目录约束而非静态选项约束() {
        ViewOptionSource source = new ViewOptionSource(
                "toolFields", "fieldPointer", "fieldLabel", Optional.of(new ViewOptionFilter("toolName", "toolName")));
        ViewStructuredItemField dynamic = new ViewStructuredItemField(
                "fieldPointer",
                "输出字段",
                ViewStructuredItemType.CHOICE,
                Optional.of("/exitCode"),
                List.of(),
                ViewStructuredItemValidation.required(true),
                List.of(),
                Optional.of(source));
        ViewStructuredListField list = new ViewStructuredListField(
                "items",
                "条目",
                new ViewBinding("editor", "items"),
                0,
                1,
                "id",
                List.of(dynamic),
                List.of(),
                Optional.empty());

        assertEquals(
                "/success",
                list.normalizeRows(List.of(Map.of("id", "one", "fieldPointer", "/success")))
                        .getFirst()
                        .get("fieldPointer"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewStructuredItemField(
                        "text",
                        "文本",
                        ViewStructuredItemType.TEXT,
                        Optional.empty(),
                        List.of(),
                        ViewStructuredItemValidation.required(false),
                        List.of(),
                        Optional.of(source)));
    }

    @Test
    void structuredValidation拒绝越界和逆序范围() {
        assertInvalidStructuredValidation(Optional.of(-1), Optional.empty(), Optional.empty(), Optional.empty());
        assertInvalidStructuredValidation(Optional.empty(), Optional.of(4_001), Optional.empty(), Optional.empty());
        assertInvalidStructuredValidation(Optional.of(3), Optional.of(2), Optional.empty(), Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewStructuredItemValidation(
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(BigDecimal.TEN),
                        Optional.of(BigDecimal.ONE),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewStructuredItemValidation(
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(33),
                        Optional.empty()));
    }

    private static void assertInvalidList(
            int minRows, int maxRows, String itemIdField, List<ViewStructuredItemField> fields) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewStructuredListField(
                        "items",
                        "条目",
                        new ViewBinding("editor", "items"),
                        minRows,
                        maxRows,
                        itemIdField,
                        fields,
                        List.of(),
                        Optional.empty()));
    }

    private static ViewStructuredListField structured(int minRows, int maxRows, List<Map<String, Object>> rows) {
        return new ViewStructuredListField(
                "items",
                "条目",
                new ViewBinding("editor", "items"),
                minRows,
                maxRows,
                "id",
                List.of(
                        item(
                                "text",
                                ViewStructuredItemType.TEXT,
                                Optional.of(""),
                                List.of(),
                                textValidation(),
                                List.of()),
                        item(
                                "number",
                                ViewStructuredItemType.NUMBER,
                                Optional.empty(),
                                List.of(),
                                required(),
                                List.of()),
                        item(
                                "enabled",
                                ViewStructuredItemType.BOOLEAN,
                                Optional.of("true"),
                                List.of(),
                                required(),
                                List.of()),
                        item(
                                "choice",
                                ViewStructuredItemType.CHOICE,
                                Optional.of("task"),
                                List.of(),
                                required(),
                                List.of(new ViewOption("task", "任务"), new ViewOption("turn", "Turn"))),
                        item(
                                "tags",
                                ViewStructuredItemType.TEXT_LIST,
                                Optional.empty(),
                                List.of(),
                                required(),
                                List.of())),
                rows,
                Optional.empty());
    }

    private static Map<String, Object> row(String id) {
        return Map.of(
                "id",
                id,
                "text",
                "alpha",
                "number",
                2.5,
                "enabled",
                true,
                "choice",
                "task",
                "tags",
                List.of("one", "two"));
    }

    private static void assertInvalidRow(ViewStructuredListField field, String key, Object value) {
        LinkedHashMap<String, Object> invalid = new LinkedHashMap<>(row("one"));
        invalid.put(key, value);
        assertThrows(IllegalArgumentException.class, () -> field.normalizeRows(List.of(invalid)));
    }

    private static ViewStructuredItemField textItem(String name) {
        return item(name, ViewStructuredItemType.TEXT, Optional.empty(), List.of(), textValidation(), List.of());
    }

    private static ViewStructuredItemField item(
            String name,
            ViewStructuredItemType type,
            Optional<String> initial,
            List<String> initialList,
            ViewStructuredItemValidation validation,
            List<ViewOption> options) {
        return new ViewStructuredItemField(
                name, "字段", type, initial, initialList, validation, options, Optional.empty());
    }

    private static ViewStructuredItemValidation required() {
        return ViewStructuredItemValidation.required(false);
    }

    private static ViewStructuredItemValidation textValidation() {
        return new ViewStructuredItemValidation(
                false,
                Optional.of(0),
                Optional.of(4_000),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static void assertInvalidStructuredValidation(
            Optional<Integer> minLength,
            Optional<Integer> maxLength,
            Optional<Integer> minItems,
            Optional<Integer> maxItems) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewStructuredItemValidation(
                        false, minLength, maxLength, Optional.empty(), Optional.empty(), minItems, maxItems));
    }
}
