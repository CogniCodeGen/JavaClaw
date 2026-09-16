package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewPageTargetSelectionTest {
    @Test
    void 按权威游标定位后页新条目且不伪造当前页行() {
        ViewPageCursorState navigation = new ViewPageCursorState();
        ViewPageTargetSelection target = new ViewPageTargetSelection();
        target.begin(schema(), navigation, "documents", Optional.of("created"));
        ViewData first = page("", "second", "old");
        assertTrue(target.advance(first, navigation));
        assertEquals("second", navigation.request(Map.of()).cursor("documents"));
        assertEquals(Optional.of("created"), navigation.request(Map.of()).selectedKey("documents"));
        assertEquals("old", first.source("documents").rows().getFirst().get("id"));
        assertFalse(target.advance(page("second", "third", "created"), navigation));
        assertFalse(target.advance(page("third", "fourth", "other"), navigation));
    }

    @Test
    void 重复游标停止定位且未声明数据源不能启动查询() {
        ViewPageCursorState navigation = new ViewPageCursorState();
        ViewPageTargetSelection target = new ViewPageTargetSelection();
        assertThrows(
                IllegalArgumentException.class,
                () -> target.begin(schema(), navigation, "unknown", Optional.of("new")));
        target.begin(schema(), navigation, "documents", Optional.of("new"));
        assertTrue(target.advance(page("", "next", "old"), navigation));
        assertThrows(IllegalStateException.class, () -> target.advance(page("next", "next", "old"), navigation));
        assertFalse(target.advance(page("next", "", "old"), navigation));
    }

    private static ViewSchema schema() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "selection",
                "目录",
                List.of(new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100)),
                List.of(new ViewSchema.ListView(
                        "items", "项目", "documents", "id", "id", "id", ViewSelectionMode.SINGLE, List.of())));
    }

    private static ViewData page(String cursor, String next, String id) {
        return new ViewData(Map.of(
                "documents",
                new ViewData.Source(
                        List.of(Map.of("id", id)), Map.of(), cursor, next, !next.isEmpty(), 1, 0, Optional.empty())));
    }
}
