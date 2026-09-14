package com.javaclaw.desktop.web;

import javafx.scene.web.WebView;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebChatViewStateTest {
    @Test
    void 展开和阅读状态最多保留最近六十四个会话且更新会话刷新保留顺序() {
        WebChatViewState state = new WebChatViewState();
        for (int index = 0; index < 64; index++) {
            assertTrue(state.save("thread-" + index, "state-" + index));
        }
        assertTrue(state.save("thread-0", "recent"));
        assertTrue(state.save("thread-64", "new"));
        assertEquals("recent", state.get("thread-0"));
        assertEquals("", state.get("thread-1"));
        for (int index = 2; index < 64; index++) {
            assertEquals("state-" + index, state.get("thread-" + index));
        }
        assertEquals("new", state.get("thread-64"));
        state.clear();
        assertEquals("", state.get("thread-0"));
        assertEquals("", state.get("thread-64"));
    }

    @Test
    void 状态长度上限按UTF16计算且超限提交不覆盖已保存状态() {
        WebChatViewState state = new WebChatViewState();
        String boundary = "展".repeat(32_768);
        assertTrue(state.save("thread", boundary));
        assertFalse(state.save("thread", boundary + "开"));
        assertEquals(boundary, state.get("thread"));
        assertFalse(state.save("other", "😀".repeat(16_385)));
        assertEquals("", state.get("other"));
    }

    @Test
    void 草稿可恢复性只注入两个布尔值并按变更去重() {
        WebChatViewState state = new WebChatViewState();
        assertFalse(state.availability(false, false));
        assertTrue(state.availability(true, false));
        assertFalse(state.availability(true, false));
        FxTestSupport.run(() -> {
            WebView view = new WebView();
            view.getEngine().executeScript("""
                    window.JavaClawOutgoingAvailability = function() {
                        window.availabilityCall = Array.from(arguments);
                    };
                    """);
            state.applyAvailability(view);
            assertEquals("[true,false]", view.getEngine().executeScript("JSON.stringify(availabilityCall)"));
            assertEquals(
                    "boolean,boolean", view.getEngine().executeScript("availabilityCall.map(v => typeof v).join(',')"));
            assertTrue(state.availability(false, true));
            state.applyAvailability(view);
            assertEquals("[false,true]", view.getEngine().executeScript("JSON.stringify(availabilityCall)"));
            view.getEngine().load(null);
        });
    }
}
