package com.javaclaw.desktop.settings;

import javafx.beans.binding.Bindings;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import com.javaclaw.extension.spi.ExtensionExecutionReceipt;

/** 后台任务名称与摘要按实际列表宽度换行；完整标识保留在辅助文本和 Tooltip 中。 */
final class AutomationJobCell extends ListCell<ExtensionExecutionReceipt> {
    private final Label title = new Label();
    private final Label summary = new Label();
    private final VBox content = new VBox(3, title, summary);

    AutomationJobCell() {
        title.setWrapText(true);
        title.setMinWidth(0);
        title.getStyleClass().add("platform-detail-title");
        summary.setWrapText(true);
        summary.setMinWidth(0);
        summary.getStyleClass().add("platform-detail-text");
        content.getStyleClass().add("platform-detail-cell");
        content.setMinWidth(0);
        content.prefWidthProperty()
                .bind(Bindings.createDoubleBinding(
                        () -> Math.max(
                                0,
                                getWidth() - getInsets().getLeft() - getInsets().getRight()),
                        widthProperty(),
                        insetsProperty()));
        content.maxWidthProperty().bind(content.prefWidthProperty());
    }

    @Override
    protected void updateItem(ExtensionExecutionReceipt job, boolean empty) {
        super.updateItem(job, empty);
        setText(null);
        if (empty || job == null) {
            setGraphic(null);
            setAccessibleText(null);
            setTooltip(null);
            return;
        }
        title.setText(job.definitionId() + " · " + SettingsLabels.executionState(job.state()));
        summary.setText(job.extensionId().value() + " · " + SettingsLabels.automationJobType(job.jobType()) + " · 版本 "
                + job.revision());
        String description = title.getText() + "\n" + summary.getText();
        setAccessibleText(description);
        Tooltip tooltip = new Tooltip(description);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(480);
        setTooltip(tooltip);
        setGraphic(content);
    }
}
