package com.javaclaw.chat;

import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 可折叠 Markdown 结果块的纯页面状态。 */
final class ExpandableMarkdownBlockViewModel {

    private final StringProperty title = new SimpleStringProperty("");
    private final BooleanProperty contentAvailable = new SimpleBooleanProperty(false);
    private final BooleanProperty expanded = new SimpleBooleanProperty(true);

    StringProperty titleProperty() {
        return title;
    }

    BooleanProperty contentAvailableProperty() {
        return contentAvailable;
    }

    BooleanProperty expandedProperty() {
        return expanded;
    }

    BooleanBinding contentVisibleBinding() {
        return contentAvailable.and(expanded);
    }

    void configure(String value, boolean initiallyAvailable) {
        title.set(value);
        contentAvailable.set(initiallyAvailable);
        expanded.set(true);
    }

    void revealContent() {
        contentAvailable.set(true);
    }

    void toggleExpanded() {
        if (contentAvailable.get()) expanded.set(!expanded.get());
    }
}
