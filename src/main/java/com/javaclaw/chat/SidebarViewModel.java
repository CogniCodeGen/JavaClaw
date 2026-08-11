package com.javaclaw.chat;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * 侧边栏的纯 JavaFX 页面状态。
 *
 * <p>不持有 Service、Repository、Controller 或 Node，可由 FXML Controller 测试独立观察。</p>
 */
public final class SidebarViewModel {

    private final StringProperty selectedSessionId = new SimpleStringProperty();
    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final BooleanProperty batchMode = new SimpleBooleanProperty(false);
    private final IntegerProperty sessionCount = new SimpleIntegerProperty(0);

    public StringProperty selectedSessionIdProperty() {
        return selectedSessionId;
    }

    public StringProperty searchQueryProperty() {
        return searchQuery;
    }

    public BooleanProperty batchModeProperty() {
        return batchMode;
    }

    public IntegerProperty sessionCountProperty() {
        return sessionCount;
    }
}
