package com.javaclaw.desktop.shell;

import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.desktop.view.PresentedItem;
import com.javaclaw.desktop.view.TranscriptPresenter;

/** 原生简版消息单元格；复用投影器并限制单行控件接收的正文长度。 */
final class ShellTranscriptCell extends ListCell<ItemEnvelope> {
    private final TranscriptPresenter presenter;
    private final Label title = new Label();
    private final Label body = new Label();
    private final VBox box = new VBox(6, title, body);

    ShellTranscriptCell(TranscriptPresenter presenter) {
        this.presenter = presenter;
        title.getStyleClass().add("message-role");
        body.setWrapText(true);
    }

    @Override
    protected void updateItem(ItemEnvelope item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            return;
        }
        PresentedItem presented = presenter.present(item);
        title.setText(presented.title());
        body.setText(presented.body().length() > 65_536 ? presented.body().substring(0, 65_536) : presented.body());
        box.getStyleClass().setAll(presented.styleClass());
        setGraphic(box);
    }
}
