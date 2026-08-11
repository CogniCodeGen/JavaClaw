package com.javaclaw.ui.javafx.memory;

/** 事实分组和事实行向所属分区提交的用户动作。 */
interface MemoryFactActions {
    void toggleSelected(String id);
    void edit(String id, String text);
    void togglePin(String id);
    void restore(String id);
    void delete(String id, String text);
}
