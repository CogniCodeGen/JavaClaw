package com.javaclaw.chat;

/** Immutable projection consumed by reusable sidebar cells. */
sealed interface SidebarSessionItem {

    record Group(String name, int count) implements SidebarSessionItem { }

    record Conversation(
            String id,
            String title,
            String timeText,
            boolean selected,
            boolean batchMode,
            boolean checked,
            boolean archived,
            String parentThreadId
    ) implements SidebarSessionItem {
        Conversation(String id, String title, String timeText, boolean selected,
                     boolean batchMode, boolean checked) {
            this(id, title, timeText, selected, batchMode, checked, false, null);
        }
    }
}
