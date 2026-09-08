package com.javaclaw.desktop;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ViewInitialSelection;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSelectionProjectionTest {
    private final List<Map<String, Object>> rows =
            List.of(Map.of("id", "first", "revision", 1), Map.of("id", "saved", "revision", 7));

    @Test
    void 非主表首次回显精确版本且用户重选优先() {
        assertEquals(Optional.of("saved"), select(hint("roles", "saved", 7), ViewLoadRequest.initial(), false));
        var changed = new ViewLoadRequest(Map.of(), Map.of("roles", "first"), Map.of());
        assertEquals(Optional.of("first"), select(hint("roles", "saved", 7), changed, false));
    }

    @Test
    void 明确清空不发送空RPC选择也不重新套用初选或主表默认() {
        var cleared = new ViewLoadRequest(Map.of(), Map.of("roles", ""), Map.of());
        assertTrue(cleared.selectionInitialized("roles"));
        assertTrue(cleared.selectedKey("roles").isEmpty());
        assertTrue(select(hint("roles", "saved", 7), cleared, true).isEmpty());
        assertTrue(select(Map.of(), cleared, true).isEmpty());
    }

    @Test
    void 删除或失效引用与未加载分页均不替换成首行() {
        assertTrue(select(hint("roles", "saved", 6), ViewLoadRequest.initial(), true)
                .isEmpty());
        assertTrue(select(hint("roles", "missing", 7), ViewLoadRequest.initial(), true)
                .isEmpty());
        assertTrue(select(hint("providers", "saved", 7), ViewLoadRequest.initial(), true)
                .isEmpty());
        var removed = new ViewLoadRequest(Map.of(), Map.of("roles", "removed"), Map.of());
        assertTrue(select(Map.of(), removed, true).isEmpty());
    }

    @Test
    void 没有初选的旧主从页面保留默认且非主表保留显式选择() {
        assertEquals(Optional.of("first"), select(Map.of(), ViewLoadRequest.initial(), true));
        assertTrue(select(Map.of(), ViewLoadRequest.initial(), false).isEmpty());
        assertTrue(ViewSelectionProjection.select("roles", null, rows, Map.of(), ViewLoadRequest.initial(), true)
                .isEmpty());
    }

    @Test
    void 非整数版本损坏及未来元数据保持未选() {
        Map<String, Object> future = Map.of(ViewInitialSelection.VALUES_KEY, Map.of("version", 2));
        assertTrue(select(future, ViewLoadRequest.initial(), true).isEmpty());
        var fractional = List.<Map<String, Object>>of(Map.of("id", "saved", "revision", 7.1));
        assertTrue(ViewSelectionProjection.select(
                        "roles", "id", fractional, hint("roles", "saved", 7), ViewLoadRequest.initial(), true)
                .isEmpty());
    }

    private Optional<String> select(Map<String, Object> values, ViewLoadRequest request, boolean master) {
        return ViewSelectionProjection.select("roles", "id", rows, values, request, master);
    }

    @Test
    void 自动回显后同ID目录更新不能静默升级且新的服务端提示不覆盖首次引用() {
        var initial = new CanonicalJson().encode(new ViewInitialSelection(1, "roles", "saved", "revision", 6));
        var page = new ViewLoadRequest(Map.of(), Map.of(), Map.of(), Map.of(), Map.of("roles", initial));
        assertTrue(select(hint("roles", "saved", 7), page, true).isEmpty());
        var explicit =
                new ViewLoadRequest(Map.of(), Map.of("roles", "saved"), Map.of(), Map.of(), Map.of("roles", initial));
        assertEquals(Optional.of("saved"), select(hint("roles", "saved", 7), explicit, false));
    }

    private Map<String, Object> hint(String source, String key, long revision) {
        return Map.of(ViewInitialSelection.VALUES_KEY, new ViewInitialSelection(1, source, key, "revision", revision));
    }
}
