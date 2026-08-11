package com.javaclaw.chat;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 流式助手消息的纯 JavaFX 状态；不持有业务服务或场景节点。 */
final class AssistantMessageViewModel {

    private final StringProperty agentName = new SimpleStringProperty("");
    private final StringProperty modelName = new SimpleStringProperty("");
    private final StringProperty timestamp = new SimpleStringProperty("");
    private final StringProperty metadata = new SimpleStringProperty("—");
    private final BooleanProperty toolsVisible = new SimpleBooleanProperty(false);
    private final BooleanProperty replyVisible = new SimpleBooleanProperty(false);
    private final BooleanProperty replyCardVisible = new SimpleBooleanProperty(true);
    private final BooleanProperty adoptionEnabled = new SimpleBooleanProperty(false);

    StringProperty agentNameProperty() {
        return agentName;
    }

    StringProperty modelNameProperty() {
        return modelName;
    }

    StringProperty timestampProperty() {
        return timestamp;
    }

    StringProperty metadataProperty() {
        return metadata;
    }

    BooleanProperty toolsVisibleProperty() {
        return toolsVisible;
    }

    BooleanProperty replyVisibleProperty() {
        return replyVisible;
    }

    BooleanProperty replyCardVisibleProperty() {
        return replyCardVisible;
    }

    BooleanProperty adoptionEnabledProperty() {
        return adoptionEnabled;
    }

    void configure(String agent, String model, String time) {
        agentName.set(agent);
        modelName.set(model);
        timestamp.set(time);
    }

    void showTools() {
        toolsVisible.set(true);
    }

    void revealReply() {
        replyVisible.set(true);
    }

    void hideReplyCard() {
        replyCardVisible.set(false);
    }

    void enableAdoption() {
        adoptionEnabled.set(true);
    }
}
