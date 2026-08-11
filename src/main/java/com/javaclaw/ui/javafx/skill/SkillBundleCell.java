package com.javaclaw.ui.javafx.skill;

import com.javaclaw.application.skill.SkillManagementApplicationService.BundleItem;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

/** 技能包列表 Cell；FXML 只在构造时加载一次。 */
public final class SkillBundleCell extends ListCell<BundleItem> {

    @FXML private HBox root;
    @FXML private Label statusDot;
    @FXML private Label nameLabel;
    @FXML private Label skillsLabel;
    @FXML private Label stateBadge;

    SkillBundleCell() {
        HBox loaded = EmbeddedFxmlLoader.load(
                SkillBundleCell.class.getResource("/fxml/skill/skill-bundle-cell.fxml"),
                this, HBox.class);
        if (loaded != root) throw new IllegalStateException("技能包 Cell FXML 根节点不一致");
        selectedProperty().addListener((ignored, previous, selected) -> updateSelection(selected));
    }

    @Override
    protected void updateItem(BundleItem item, boolean empty) {
        super.updateItem(item, empty);
        setText(null);
        if (empty || item == null) {
            setGraphic(null);
            return;
        }
        statusDot.setText(item.enabled() ? "●" : "○");
        statusDot.getStyleClass().removeAll("skill-dot-on", "skill-dot-off");
        statusDot.getStyleClass().add(item.enabled() ? "skill-dot-on" : "skill-dot-off");
        nameLabel.setText(item.name());
        skillsLabel.setText(item.skills().isEmpty() ? "（暂无技能）" : String.join(" · ", item.skills()));
        stateBadge.setText(item.enabled() ? "启用" : "停用");
        stateBadge.getStyleClass().removeAll("jc-badge-running", "jc-badge-stopped");
        stateBadge.getStyleClass().add(item.enabled() ? "jc-badge-running" : "jc-badge-stopped");
        setGraphic(root);
        updateSelection(isSelected());
    }

    private void updateSelection(boolean selected) {
        root.getStyleClass().remove("modal-nav-btn-selected");
        if (selected) root.getStyleClass().add("modal-nav-btn-selected");
    }
}
