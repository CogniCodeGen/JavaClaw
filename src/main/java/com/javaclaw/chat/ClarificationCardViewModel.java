package com.javaclaw.chat;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 主动澄清卡片的标题、正文和可见性状态。 */
final class ClarificationCardViewModel {

    private final StringProperty agentName = new SimpleStringProperty("");
    private final StringProperty modelName = new SimpleStringProperty("");
    private final StringProperty timestamp = new SimpleStringProperty("");
    private final BooleanProperty reasonVisible = new SimpleBooleanProperty(false);
    private final BooleanProperty questionVisible = new SimpleBooleanProperty(false);

    void configure(String agent, String model, String time, String reason, String question) {
        agentName.set(agent);
        modelName.set(model);
        timestamp.set(time);
        reasonVisible.set(reason != null && !reason.isBlank());
        questionVisible.set(question != null && !question.isBlank());
    }

    StringProperty agentNameProperty() { return agentName; }
    StringProperty modelNameProperty() { return modelName; }
    StringProperty timestampProperty() { return timestamp; }
    BooleanProperty reasonVisibleProperty() { return reasonVisible; }
    BooleanProperty questionVisibleProperty() { return questionVisible; }
}
