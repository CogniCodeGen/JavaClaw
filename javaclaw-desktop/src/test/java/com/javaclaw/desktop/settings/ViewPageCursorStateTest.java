package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.extension.spi.ViewInitialSelection;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewPageCursorStateTest {
    @Test
    void 自动初选版本只记录一次并与明确清空分别保存() {
        var state = new ViewPageCursorState();
        state.acceptInitialSelections(data(new ViewInitialSelection(1, "roles", "saved", "revision", 1)));
        state.acceptInitialSelections(data(new ViewInitialSelection(1, "roles", "saved", "revision", 2)));
        var request = state.request(Map.of());
        assertFalse(request.selectionInitialized("roles"));
        assertEquals(
                1,
                new CanonicalJson()
                        .decode(request.initialSelections().get("roles"), ViewInitialSelection.class)
                        .revision());
        state.selections.put("roles", "");
        assertTrue(state.request(Map.of()).selectionInitialized("roles"));
        state.clear();
        assertTrue(state.request(Map.of()).initialSelections().isEmpty());
    }

    @Test
    void 损坏标量提示不能中断页面或在后续刷新自动换成有效引用() {
        var state = new ViewPageCursorState();
        state.acceptInitialSelections(data("bad"));
        state.acceptInitialSelections(data(new ViewInitialSelection(1, "roles", "saved", "revision", 1)));
        assertEquals(
                "{}", state.request(Map.of()).initialSelections().get("roles").json());
    }

    private ViewData data(Object hint) {
        return new ViewData(Map.of(
                "roles",
                new ViewData.Source(
                        List.of(),
                        Map.of(ViewInitialSelection.VALUES_KEY, hint),
                        "",
                        "",
                        false,
                        1,
                        0,
                        Optional.empty())));
    }
}
