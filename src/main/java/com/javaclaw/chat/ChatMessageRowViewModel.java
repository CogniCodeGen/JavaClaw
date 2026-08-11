package com.javaclaw.chat;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 单条静态聊天消息的纯 JavaFX 页面状态。 */
final class ChatMessageRowViewModel {

    private final StringProperty author = new SimpleStringProperty("");
    private final StringProperty model = new SimpleStringProperty("");
    private final StringProperty timestamp = new SimpleStringProperty("");
    private final StringProperty metadata = new SimpleStringProperty("—");
    private final BooleanProperty normal = new SimpleBooleanProperty(false);
    private final BooleanProperty system = new SimpleBooleanProperty(false);
    private final BooleanProperty welcome = new SimpleBooleanProperty(false);
    private final BooleanProperty user = new SimpleBooleanProperty(false);
    private final BooleanProperty assistant = new SimpleBooleanProperty(false);
    private final BooleanProperty plainText = new SimpleBooleanProperty(false);
    private final BooleanProperty markdown = new SimpleBooleanProperty(false);
    private final BooleanProperty attachments = new SimpleBooleanProperty(false);

    void configure(
            ChatMessageRowFactory.Variant variant,
            String authorName,
            String modelName,
            String time,
            String meta,
            boolean hasText,
            boolean hasAttachments) {
        author.set(authorName);
        model.set(modelName);
        timestamp.set(time);
        metadata.set(meta == null ? "—" : meta);
        normal.set(variant == ChatMessageRowFactory.Variant.USER
                || variant == ChatMessageRowFactory.Variant.ASSISTANT);
        system.set(variant == ChatMessageRowFactory.Variant.SYSTEM);
        welcome.set(variant == ChatMessageRowFactory.Variant.WELCOME);
        user.set(variant == ChatMessageRowFactory.Variant.USER);
        assistant.set(variant == ChatMessageRowFactory.Variant.ASSISTANT);
        plainText.set(hasText && variant == ChatMessageRowFactory.Variant.USER);
        markdown.set(variant == ChatMessageRowFactory.Variant.ASSISTANT);
        attachments.set(hasAttachments);
    }

    StringProperty authorProperty() { return author; }
    StringProperty modelProperty() { return model; }
    StringProperty timestampProperty() { return timestamp; }
    StringProperty metadataProperty() { return metadata; }
    BooleanProperty normalProperty() { return normal; }
    BooleanProperty systemProperty() { return system; }
    BooleanProperty welcomeProperty() { return welcome; }
    BooleanProperty userProperty() { return user; }
    BooleanProperty assistantProperty() { return assistant; }
    BooleanProperty plainTextProperty() { return plainText; }
    BooleanProperty markdownProperty() { return markdown; }
    BooleanProperty attachmentsProperty() { return attachments; }
}
