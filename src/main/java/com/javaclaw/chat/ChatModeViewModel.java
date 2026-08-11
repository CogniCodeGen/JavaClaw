package com.javaclaw.chat;

import com.javaclaw.api.conversation.PlanProfile;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** 聊天模式栏页面状态；不持有 Service、Repository 或 JavaFX Node。 */
final class ChatModeViewModel {

    private final ObservableList<ModeChoice> modes = FXCollections.observableArrayList();
    private final ObservableList<WorkflowChoice> workflows = FXCollections.observableArrayList();
    private final StringProperty selectedModeId = new SimpleStringProperty("chat");
    private final StringProperty selectedWorkflowId = new SimpleStringProperty();
    private final ObjectProperty<PlanProfile> planProfile =
            new SimpleObjectProperty<>(PlanProfile.AUTO);

    ObservableList<ModeChoice> modes() {
        return modes;
    }

    ObservableList<WorkflowChoice> workflows() {
        return workflows;
    }

    StringProperty selectedModeIdProperty() {
        return selectedModeId;
    }

    StringProperty selectedWorkflowIdProperty() {
        return selectedWorkflowId;
    }

    ObjectProperty<PlanProfile> planProfileProperty() {
        return planProfile;
    }

    record ModeChoice(String id, String label, String tooltip) {
        ModeChoice {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("模式 id 不能为空");
            label = label == null || label.isBlank() ? id : label;
            tooltip = tooltip == null ? "" : tooltip;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    record WorkflowChoice(String id, String name) {
        WorkflowChoice {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("工作流 id 不能为空");
            name = name == null || name.isBlank() ? id : name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
