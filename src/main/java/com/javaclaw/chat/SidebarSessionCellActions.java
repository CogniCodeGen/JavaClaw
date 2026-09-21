package com.javaclaw.chat;

/** Events emitted by a reusable sidebar session cell. */
interface SidebarSessionCellActions {

    void activate(String sessionId);

    void delete(String sessionId);

    default void archive(String sessionId) { }
    default void resume(String sessionId) { }
    default void fork(String sessionId) { }
    default void inspect(String sessionId) { }

    void checked(String sessionId, boolean selected);
}
