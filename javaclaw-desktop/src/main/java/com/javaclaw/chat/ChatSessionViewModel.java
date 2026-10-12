package com.javaclaw.chat;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;

/** 当前会话转录区域的滚动和未读状态；不持有服务或 JavaFX Node。 */
final class ChatSessionViewModel {

    private final BooleanProperty followTail = new SimpleBooleanProperty(true);
    private final IntegerProperty unreadMessages = new SimpleIntegerProperty(0);

    BooleanProperty followTailProperty() {
        return followTail;
    }

    IntegerProperty unreadMessagesProperty() {
        return unreadMessages;
    }

    void incrementUnread(int delta) {
        if (delta > 0) unreadMessages.set(unreadMessages.get() + delta);
    }

    void resetUnread() {
        unreadMessages.set(0);
    }
}
