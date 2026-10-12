package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.Candidate;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

/** 历史测试目录候选 Cell；FXML 模板只加载一次，复用时只替换文本。 */
public final class TestDataCandidateCell extends ListCell<Candidate> {

    @FXML private HBox root;
    @FXML private Label pathLabel;
    @FXML private Label sizeLabel;

    public TestDataCandidateCell() {
        HBox content = EmbeddedFxmlLoader.load(
                TestDataCandidateCell.class.getResource(
                        "/fxml/settings/test-data-candidate-cell.fxml"),
                this, HBox.class);
        if (content != root) {
            throw new IllegalStateException("测试数据候选 Cell FXML 根节点注入不一致");
        }
    }

    @Override
    protected void updateItem(Candidate candidate, boolean empty) {
        super.updateItem(candidate, empty);
        setText(null);
        if (empty || candidate == null) {
            setGraphic(null);
            return;
        }
        pathLabel.setText(candidate.path().toString());
        sizeLabel.setText(SettingsValueFormatter.humanReadableBytes(candidate.bytes()));
        setGraphic(root);
    }
}
