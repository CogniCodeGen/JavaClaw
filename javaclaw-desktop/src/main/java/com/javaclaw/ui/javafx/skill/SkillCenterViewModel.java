package com.javaclaw.ui.javafx.skill;

import com.javaclaw.application.skill.SkillManagementApplicationService.Snapshot;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 技能中心导航状态；不持有应用服务或可变领域对象。 */
final class SkillCenterViewModel {

    enum Panel { EMPTY, EDITOR, PROPOSALS, BUNDLES }

    private final ObjectProperty<Snapshot> snapshot = new SimpleObjectProperty<>();
    private final ObjectProperty<Panel> panel = new SimpleObjectProperty<>(Panel.EMPTY);
    private final StringProperty selectedSkillId = new SimpleStringProperty("");

    ObjectProperty<Snapshot> snapshotProperty() { return snapshot; }
    ObjectProperty<Panel> panelProperty() { return panel; }
    StringProperty selectedSkillIdProperty() { return selectedSkillId; }

    void apply(Snapshot value) {
        snapshot.set(value);
        String selected = selectedSkillId.get();
        if (selected != null && !selected.isBlank() && value.find(selected) == null) {
            selectedSkillId.set("");
            panel.set(Panel.EMPTY);
        }
    }

    void selectSkill(String id) {
        selectedSkillId.set(id == null ? "" : id);
        panel.set(id == null || id.isBlank() ? Panel.EMPTY : Panel.EDITOR);
    }

    void show(Panel target) {
        selectedSkillId.set("");
        panel.set(target);
    }
}
