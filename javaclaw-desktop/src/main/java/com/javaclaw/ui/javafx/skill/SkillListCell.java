package com.javaclaw.ui.javafx.skill;

import com.javaclaw.application.skill.SkillManagementApplicationService.SkillSummary;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/** 虚拟化技能列表 Cell；构造时加载一次模板，复用时只更新快照。 */
public final class SkillListCell extends ListCell<SkillSummary> {

    @FXML private HBox root;
    @FXML private Label statusDot;
    @FXML private Label nameLabel;

    SkillListCell() {
        HBox loaded = EmbeddedFxmlLoader.load(
                SkillListCell.class.getResource("/fxml/skill/skill-list-cell.fxml"),
                this, HBox.class);
        if (loaded != root) throw new IllegalStateException("技能列表 Cell FXML 根节点不一致");
        selectedProperty().addListener((ignored, oldValue, selected) -> updateSelection(selected));
    }

    @Override
    protected void updateItem(SkillSummary item, boolean empty) {
        super.updateItem(item, empty);
        setText(null);
        if (empty || item == null) {
            setGraphic(null);
            return;
        }
        statusDot.setText(item.enabled() ? "●" : "○");
        statusDot.getStyleClass().removeAll("skill-dot-on", "skill-dot-off");
        statusDot.getStyleClass().add(item.enabled() ? "skill-dot-on" : "skill-dot-off");
        nameLabel.setText((item.agentCreated() ? "🤖 " : "") + item.name());
        String tags = item.tags().isEmpty() ? "" : " · " + String.join("/", item.tags());
        String description = item.description().isBlank() ? "" : item.description() + "\n";
        nameLabel.setTooltip(new Tooltip(description + "v" + item.version()
                + " · " + item.source() + tags));
        setAccessibleText(item.name() + (item.enabled() ? "，已启用" : "，已停用"));
        setGraphic(root);
        updateSelection(isSelected());
    }

    private void updateSelection(boolean selected) {
        root.getStyleClass().remove("modal-nav-btn-selected");
        if (selected) root.getStyleClass().add("modal-nav-btn-selected");
    }
}
