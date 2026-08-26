package com.javaclaw.chat;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Thinking Panel 的纯页面状态。
 *
 * <p>实例归一次 FXML 加载所有，只能在 JavaFX Application Thread 修改；它不持有服务、
 * Repository 或 Node，因此状态转换可以脱离布局独立测试。</p>
 */
public final class ThinkingPanelViewModel {

    private final StringProperty statusType = new SimpleStringProperty("idle");
    private final StringProperty statusText = new SimpleStringProperty("等待中");
    private final StringProperty elapsed = new SimpleStringProperty("0.0s");
    private final LongProperty tokensIn = new SimpleLongProperty();
    private final LongProperty tokensOut = new SimpleLongProperty();
    private final StringProperty tokenDetails =
            new SimpleStringProperty("缓存 0 · 写入 0 · 推理 —");
    private final BooleanProperty empty = new SimpleBooleanProperty(true);

    StringProperty statusTypeProperty() {
        return statusType;
    }

    StringProperty statusTextProperty() {
        return statusText;
    }

    StringProperty elapsedProperty() {
        return elapsed;
    }

    LongProperty tokensInProperty() {
        return tokensIn;
    }

    LongProperty tokensOutProperty() {
        return tokensOut;
    }

    StringProperty tokenDetailsProperty() {
        return tokenDetails;
    }

    BooleanProperty emptyProperty() {
        return empty;
    }

    void setStatus(String type, String text) {
        statusType.set(type == null || type.isBlank() ? "idle" : type);
        statusText.set(text == null ? "" : text);
    }

    void setElapsed(String value) {
        elapsed.set(value);
    }

    void setMetrics(long input, long output, String detailsText) {
        tokensIn.set(input);
        tokensOut.set(output);
        if (detailsText != null) {
            tokenDetails.set(detailsText);
        }
    }

    void setEmpty(boolean value) {
        empty.set(value);
    }
}
