package com.javaclaw.ui.javafx.knowledge;

import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

/** State adapter for the FXML text-import dialog. */
public final class KnowledgeTextImportDialogController {
    @FXML private TextField titleField;
    @FXML private TextArea contentArea;

    KnowledgeTextImportDialogFactory.Draft draft() {
        return new KnowledgeTextImportDialogFactory.Draft(
                titleField.getText(), contentArea.getText());
    }

    void requestFocus() { contentArea.requestFocus(); }
}
