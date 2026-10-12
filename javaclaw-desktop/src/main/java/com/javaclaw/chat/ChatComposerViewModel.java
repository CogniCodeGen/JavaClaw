package com.javaclaw.chat;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.File;
import java.util.Collection;
import java.util.List;

/** Composer 页面状态；不持有服务、Repository 或 JavaFX Node。 */
final class ChatComposerViewModel {

    private final BooleanProperty streaming = new SimpleBooleanProperty(false);
    private final BooleanProperty blocked = new SimpleBooleanProperty(false);
    private final BooleanProperty interactionWaiting = new SimpleBooleanProperty(false);
    private final BooleanProperty interactionAnswerEnabled = new SimpleBooleanProperty(false);
    private final BooleanProperty interactionCommandPending = new SimpleBooleanProperty(false);
    private final StringProperty interactionCaption = new SimpleStringProperty("");
    private final BooleanProperty thinking = new SimpleBooleanProperty(false);
    private final StringProperty thinkingText =
            new SimpleStringProperty("助手正在思考中...");
    private final ObservableList<File> attachments = FXCollections.observableArrayList();

    BooleanProperty streamingProperty() {
        return streaming;
    }

    BooleanProperty blockedProperty() {
        return blocked;
    }

    BooleanProperty interactionWaitingProperty() { return interactionWaiting; }
    BooleanProperty interactionAnswerEnabledProperty() { return interactionAnswerEnabled; }
    BooleanProperty interactionCommandPendingProperty() { return interactionCommandPending; }
    StringProperty interactionCaptionProperty() { return interactionCaption; }

    BooleanProperty thinkingProperty() {
        return thinking;
    }

    StringProperty thinkingTextProperty() {
        return thinkingText;
    }

    ObservableList<File> attachments() {
        return attachments;
    }

    void addAttachments(Collection<File> files) {
        for (File file : files) {
            if (file != null && !attachments.contains(file)) attachments.add(file);
        }
    }

    List<File> attachmentSnapshot() {
        return List.copyOf(attachments);
    }
}
