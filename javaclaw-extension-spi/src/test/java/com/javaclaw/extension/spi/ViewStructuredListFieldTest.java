package com.javaclaw.extension.spi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewStructuredListFieldTest {
    @Test
    void normalizesDeclaredScalarRowsAndCreatesStableKeyedDefaults() {
        ViewStructuredListField field = field(1, 3, List.of(row("first", "Alpha", 2, List.of("one"))));

        Map<String, Object> initial = field.initialRows().getFirst();
        assertEquals("first", initial.get("itemId"));
        assertEquals(new BigDecimal("2"), initial.get("weight"));
        assertEquals(List.of("one"), initial.get("tags"));
        assertInstanceOf(Boolean.class, initial.get("enabled"));

        Map<String, Object> added = field.newItem("second");
        assertEquals("second", added.get("itemId"));
        assertEquals("", added.get("name"));
        assertEquals(List.of(), added.get("tags"));
    }

    @Test
    void rejectsRowsOutsideBoundsUnknownFieldsNestedValuesAndDuplicateKeys() {
        ViewStructuredListField field = field(1, 3, List.of(row("first", "Alpha", 2, List.of("one"))));

        assertThrows(IllegalArgumentException.class, () -> field.normalizeRows(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> field.normalizeRows(List.of(
                        row("a", "A", 1, List.of()),
                        row("b", "B", 2, List.of()),
                        row("c", "C", 3, List.of()),
                        row("d", "D", 4, List.of()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> field.normalizeRows(List.of(row("same", "A", 1, List.of()), row("same", "B", 2, List.of()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> field.normalizeRows(List.of(Map.of(
                        "itemId", "one",
                        "name", "A",
                        "weight", 1,
                        "enabled", true,
                        "kind", "task",
                        "tags", List.of(),
                        "className", "java.lang.Runtime"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> field.normalizeRows(List.of(row("one", "A", 1, List.of(List.of("nested"))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> field.normalizeRows(List.of(row("one", "A", Map.of("nested", 1), List.of()))));
    }

    @Test
    void rejectsNestedFieldKindsAndExcessiveRenderedInputCount() {
        List<ViewStructuredItemField> fields = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            fields.add(text("field" + index, "字段" + index));
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewStructuredListField(
                        "items",
                        "条目",
                        new ViewBinding("editor", "items"),
                        0,
                        100,
                        "itemId",
                        fields,
                        List.of(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewStructuredItemField(
                        "name",
                        "名称",
                        ViewStructuredItemType.TEXT,
                        Optional.empty(),
                        List.of(),
                        new ViewStructuredItemValidation(
                                false,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(2)),
                        List.of()));
    }

    private static ViewStructuredListField field(int minRows, int maxRows, List<Map<String, Object>> initialRows) {
        return new ViewStructuredListField(
                "items",
                "条目",
                new ViewBinding("editor", "items"),
                minRows,
                maxRows,
                "itemId",
                List.of(text("name", "名称"), number(), booleanField(), choice(), textList()),
                initialRows,
                Optional.empty());
    }

    private static ViewStructuredItemField text(String name, String label) {
        return new ViewStructuredItemField(
                name,
                label,
                ViewStructuredItemType.TEXT,
                Optional.empty(),
                List.of(),
                ViewStructuredItemValidation.required(true),
                List.of());
    }

    private static ViewStructuredItemField number() {
        return new ViewStructuredItemField(
                "weight",
                "权重",
                ViewStructuredItemType.NUMBER,
                Optional.empty(),
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

    private static ViewStructuredItemField booleanField() {
        return new ViewStructuredItemField(
                "enabled",
                "启用",
                ViewStructuredItemType.BOOLEAN,
                Optional.of("true"),
                List.of(),
                ViewStructuredItemValidation.required(false),
                List.of());
    }

    private static ViewStructuredItemField choice() {
        return new ViewStructuredItemField(
                "kind",
                "类型",
                ViewStructuredItemType.CHOICE,
                Optional.of("task"),
                List.of(),
                ViewStructuredItemValidation.required(true),
                List.of(new ViewOption("task", "任务"), new ViewOption("turn", "Turn")));
    }

    private static ViewStructuredItemField textList() {
        return new ViewStructuredItemField(
                "tags",
                "标签",
                ViewStructuredItemType.TEXT_LIST,
                Optional.empty(),
                List.of(),
                new ViewStructuredItemValidation(
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(4)),
                List.of());
    }

    private static Map<String, Object> row(String id, String name, Object weight, Object tags) {
        return Map.of(
                "itemId", id,
                "name", name,
                "weight", weight,
                "enabled", true,
                "kind", "task",
                "tags", tags);
    }
}
