package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** A host stop, never evidence that a previously dispatched action had no business effect. */
public final class DesktopNoProgressException extends RuntimeException {
    private final List<String> evidenceRefs;

    DesktopNoProgressException(List<String> evidenceRefs) {
        super("DESKTOP_NO_PROGRESS: the same desktop click was accepted but fresh observations "
                + "still show the same frame; stop repeating input and clarify the interaction method");
        this.evidenceRefs = List.copyOf(evidenceRefs);
    }

    public ObjectNode context() {
        ObjectNode context = JsonNodeFactory.instance.objectNode()
                .put("kind", "desktop.no_progress")
                .put("reasonCode", "DESKTOP_NO_PROGRESS")
                .put("text", "重复点击后界面仍未变化，已停止继续输入。先前点击的业务效果尚未核实；需确认交互方式后继续。")
                .put("dispatchAttempted", false)
                .put("priorEffectsReconciled", false);
        evidenceRefs.stream().distinct().limit(8).forEach(context.putArray("evidenceRefs")::add);
        return context;
    }
}
