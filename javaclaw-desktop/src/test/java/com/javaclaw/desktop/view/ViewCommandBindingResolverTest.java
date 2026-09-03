package com.javaclaw.desktop.view;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewCommandBindingResolverTest {
    @Test
    void 只跟踪声明的单选来源且仅在选中行变化时通知() {
        Map<String, Object> first = Map.of("id", "one", "name", "第一项", "revision", 1);
        Map<String, Object> second = Map.of("id", "two", "name", "第二项", "revision", 2);
        ViewCommandBindingResolver resolver = new ViewCommandBindingResolver(
                selectionSchema("id"), data(List.of(first, second), Map.of(), Optional.of("one")));
        AtomicInteger notifications = new AtomicInteger();
        resolver.observe(notifications::incrementAndGet);

        ViewAction selectedName = boundAction("selectedName", "rows", "name");
        assertEquals(
                "第一项",
                resolver.invocation(selectedName, Map.of(), Map.of())
                        .arguments()
                        .get("selectedName"));

        resolver.select("not-selectable", second);
        resolver.select("rows", first);
        assertEquals(0, notifications.get());

        resolver.select("rows", second);
        resolver.select("rows", null);
        resolver.select("rows", null);
        assertEquals(2, notifications.get());
        assertTrue(resolver.unavailable(selectedName, Map.of()).orElseThrow().contains("rows.name"));
    }

    @Test
    void 缺少行字段或权威字段时拒绝执行并给出具体原因() {
        ViewCommandBindingResolver resolver = new ViewCommandBindingResolver(
                selectionSchema("id"), data(List.of(), Map.of("authority", " "), Optional.empty()));
        ViewAction rowArgument = new ViewAction(
                "归档", "row/archive", Map.of(), Map.of("rowId", "id"), new ExpectedRevisionBinding.None(), false);
        ViewAction rowRevision = new ViewAction(
                "更新", "row/update", Map.of(), Map.of(), new ExpectedRevisionBinding.RowField("revision"), false);
        ViewAction authority = boundAction("authority", "rows", "authority");

        assertTrue(resolver.unavailable(rowArgument, Map.of()).orElseThrow().contains("id"));
        assertTrue(resolver.unavailable(rowRevision, Map.of()).orElseThrow().contains("revision"));
        assertTrue(resolver.unavailable(authority, Map.of()).orElseThrow().contains("rows.authority"));
        assertThrows(IllegalArgumentException.class, () -> resolver.invocation(rowArgument, Map.of(), Map.of()));
    }

    @Test
    void 行revision仅接受非负整数() {
        ViewCommandBindingResolver resolver =
                new ViewCommandBindingResolver(selectionSchema("id"), data(List.of(), Map.of(), Optional.empty()));
        ViewAction action = new ViewAction(
                "更新", "row/update", Map.of(), Map.of(), new ExpectedRevisionBinding.RowField("revision"), false);

        assertThrows(
                IllegalArgumentException.class, () -> resolver.invocation(action, Map.of(), Map.of("revision", "1")));
        assertThrows(
                IllegalArgumentException.class, () -> resolver.invocation(action, Map.of(), Map.of("revision", -1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.invocation(action, Map.of(), Map.of("revision", new BigDecimal("1.5"))));
        assertEquals(
                3, resolver.invocation(action, Map.of(), Map.of("revision", 3L)).expectedRevision());
    }

    @Test
    void 同一来源的多个单选控件必须共享稳定键字段() {
        ViewDataSource rows = source();
        ViewSchema sameKey = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "same-key",
                "同键选择",
                List.of(rows),
                List.of(list(ViewSelectionMode.SINGLE), table("id", ViewSelectionMode.SINGLE), card()));
        new ViewCommandBindingResolver(sameKey, data(List.of(), Map.of(), Optional.empty()));

        ViewSchema noSelection = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "no-selection",
                "不选择",
                List.of(rows),
                List.of(list(ViewSelectionMode.NONE)));
        new ViewCommandBindingResolver(noSelection, data(List.of(), Map.of(), Optional.empty()));

        ViewSchema conflicting = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "conflicting-key",
                "冲突键",
                List.of(rows),
                List.of(list(ViewSelectionMode.SINGLE), table("name", ViewSelectionMode.SINGLE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewCommandBindingResolver(conflicting, data(List.of(), Map.of(), Optional.empty())));
    }

    private static ViewSchema selectionSchema(String keyField) {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "selection",
                "选择",
                List.of(source()),
                List.of(table(keyField, ViewSelectionMode.SINGLE)));
    }

    private static ViewSchema.ListView list(ViewSelectionMode selection) {
        return new ViewSchema.ListView("list", "列表", "rows", "id", "name", "name", selection, List.of());
    }

    private static ViewSchema.Table table(String keyField, ViewSelectionMode selection) {
        return new ViewSchema.Table(
                "table-" + keyField,
                "表格",
                "rows",
                keyField,
                List.of(new ViewSchema.Column("name", "名称", Optional.empty())),
                selection,
                List.of());
    }

    private static ViewSchema.Card card() {
        return new ViewSchema.Card("card", "说明", "不产生选择", List.of());
    }

    private static ViewDataSource source() {
        return new ViewDataSource("rows", "row/list", Map.of(), List.of(), 20);
    }

    private static ViewData data(
            List<Map<String, Object>> rows, Map<String, Object> values, Optional<String> selectedKey) {
        return new ViewData(Map.of("rows", new ViewData.Source(rows, values, "", "", false, 7, 0, selectedKey)));
    }

    private static ViewAction boundAction(String argument, String source, String field) {
        return new ViewAction(
                "执行",
                "row/run",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.None(),
                false,
                new ViewCommandBinding(argument, new ViewBinding(source, field)));
    }
}
