package com.javaclaw.desktop.web;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.web.WebView;

/** 有界保存聊天阅读与展开状态；不保存正文或业务权限，按钮可用性始终由原生状态重新提供。 */
final class WebChatViewState {
    private final Map<String, String> contexts = new LinkedHashMap<>();
    private boolean retry;
    private boolean restore;

    String get(String context) {
        return contexts.getOrDefault(context, "");
    }

    boolean save(String context, String value) {
        if (value.length() > 32_768) {
            return false;
        }
        contexts.remove(context);
        contexts.put(context, value);
        while (contexts.size() > 64) {
            contexts.remove(contexts.keySet().iterator().next());
        }
        return true;
    }

    boolean availability(boolean ready, boolean restoreAllowed) {
        boolean changed = retry != ready || restore != restoreAllowed;
        retry = ready;
        restore = restoreAllowed;
        return changed;
    }

    void applyAvailability(WebView web) {
        web.getEngine()
                .executeScript("if (window.JavaClawOutgoingAvailability) { window.JavaClawOutgoingAvailability(" + retry
                        + "," + restore + "); }");
    }

    void clear() {
        contexts.clear();
    }
}
