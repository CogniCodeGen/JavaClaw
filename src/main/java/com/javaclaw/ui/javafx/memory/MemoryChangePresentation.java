package com.javaclaw.ui.javafx.memory;

/** 变更操作的本地化标签和现有 CSS 语义类。 */
record MemoryChangePresentation(String label, String styleClass) {
    static MemoryChangePresentation of(String operation) {
        if (operation == null) return new MemoryChangePresentation("?", "jc-badge-stopped");
        return switch (operation.toUpperCase()) {
            case "ADD" -> new MemoryChangePresentation("新增", "jc-badge-ok");
            case "ADD_PENDING" -> new MemoryChangePresentation("暂存", "jc-badge-amber");
            case "UPDATE" -> new MemoryChangePresentation("编辑", "jc-badge-amber");
            case "REMOVE" -> new MemoryChangePresentation("删除", "jc-badge-fail");
            case "MERGE" -> new MemoryChangePresentation("合并", "jc-badge-indigo");
            case "PERSONA_EDIT" -> new MemoryChangePresentation("人格", "jc-badge-soft");
            case "CLEAR" -> new MemoryChangePresentation("清空", "jc-badge-fail");
            default -> new MemoryChangePresentation(operation, "jc-badge-stopped");
        };
    }
}
