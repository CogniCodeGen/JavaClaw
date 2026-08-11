package com.javaclaw.ui.javafx.task;

/** SDD 详情列表的 FXML Cell 工厂。 */
public final class SddDetailCellFactory {
    SddChangeCell change() { return new SddChangeCell(); }
    SddScenarioCell scenario() { return new SddScenarioCell(); }
    SddChecklistCell checklist() { return new SddChecklistCell(); }
    SddLogCell log() { return new SddLogCell(); }
}
