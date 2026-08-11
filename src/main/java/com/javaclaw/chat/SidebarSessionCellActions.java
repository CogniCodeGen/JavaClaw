package com.javaclaw.chat;

/** Events emitted by a reusable sidebar session cell. */
interface SidebarSessionCellActions {

    void activate(String sessionId);

    void delete(String sessionId);

    void checked(String sessionId, boolean selected);
}
