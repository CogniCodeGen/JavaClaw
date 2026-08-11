package com.javaclaw.chat;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** Top-bar title, runtime state and visibility properties; contains no services. */
public final class ChatHeaderViewModel {
    private final StringProperty title = new SimpleStringProperty("JavaClaw 工作区");
    private final StringProperty metadata = new SimpleStringProperty("—");
    private final StringProperty embedding = new SimpleStringProperty("嵌入：检查中");
    private final StringProperty embeddingDetail = new SimpleStringProperty("");
    private final BooleanProperty streaming = new SimpleBooleanProperty(false);
    private final BooleanProperty localMode = new SimpleBooleanProperty(false);
    private final BooleanProperty embeddingVisible = new SimpleBooleanProperty(true);
    private final BooleanProperty sidebarToggleVisible = new SimpleBooleanProperty(false);

    public StringProperty titleProperty() { return title; }
    public StringProperty metadataProperty() { return metadata; }
    public StringProperty embeddingProperty() { return embedding; }
    public StringProperty embeddingDetailProperty() { return embeddingDetail; }
    public BooleanProperty streamingProperty() { return streaming; }
    public BooleanProperty localModeProperty() { return localMode; }
    public BooleanProperty embeddingVisibleProperty() { return embeddingVisible; }
    public BooleanProperty sidebarToggleVisibleProperty() { return sidebarToggleVisible; }

    public void showTitle(String value, String detail) {
        title.set(value == null || value.isBlank() ? "JavaClaw 工作区" : value);
        metadata.set(detail == null || detail.isBlank() ? "—" : detail);
    }

    public void showEmbedding(String value, String detail, boolean visible) {
        embedding.set(value == null ? "嵌入：未知" : value);
        embeddingDetail.set(detail == null ? "" : detail);
        embeddingVisible.set(visible);
    }
}
