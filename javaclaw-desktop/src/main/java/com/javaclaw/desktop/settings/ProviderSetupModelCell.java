package com.javaclaw.desktop.settings;

import java.util.function.BiConsumer;

import javafx.beans.binding.Bindings;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;

/** 虚拟化目录行；勾选意图与行焦点分离，复用时从当前草稿重新投影状态。 */
final class ProviderSetupModelCell extends ListCell<String> {
    private final ProviderSetupModelSelection selection;
    private final CheckBox included = new CheckBox();
    private final Label status = new Label();
    private final VBox content = new VBox(4, included, status);

    ProviderSetupModelCell(ProviderSetupModelSelection selection, BiConsumer<String, Boolean> changed) {
        this.selection = selection;
        included.setWrapText(true);
        included.setMinWidth(0);
        included.setMaxWidth(Double.MAX_VALUE);
        status.setWrapText(true);
        status.getStyleClass().add("platform-detail-text");
        content.setMinWidth(0);
        content.getStyleClass().add("platform-detail-cell");
        included.setOnAction(event -> {
            String id = getItem();
            if (id != null) {
                changed.accept(id, included.isSelected());
            }
        });
        content.prefWidthProperty()
                .bind(Bindings.createDoubleBinding(
                        () -> Math.max(
                                0,
                                getWidth() - getInsets().getLeft() - getInsets().getRight()),
                        widthProperty(),
                        insetsProperty()));
    }

    @Override
    protected void updateItem(String id, boolean empty) {
        super.updateItem(id, empty);
        setText(null);
        if (empty || id == null) {
            setGraphic(null);
            setAccessibleText(null);
            return;
        }
        ProviderSetupModelSelection.Model model = selection.model(id);
        included.setText(model.label());
        included.setSelected(selection.selected(id));
        included.setTooltip(ProviderSetupChoices.tooltip(model.label()));
        included.setAccessibleText("保存模型 " + model.label());
        status.setText(
                model.purposes().isEmpty()
                        ? "待完善用途"
                        : ProviderSetupPurposeChoice.from(model.purposes()).label());
        setGraphic(content);
        setAccessibleText(model.label() + "，" + status.getText() + (included.isSelected() ? "，已勾选" : "，未勾选"));
    }
}
