package com.javaclaw.chat;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import java.io.File;

/** 单个附件预览的 FXML Controller；只映射文件快照和移除事件。 */
public final class AttachmentPreviewItemController implements AutoCloseable {

    @FXML private StackPane root;
    @FXML private ImageView thumbnail;
    @FXML private Label fileType;
    @FXML private Label fileName;

    private Runnable removeAction = () -> { };
    private boolean closed;

    void configure(File file, Runnable removeAction) {
        this.removeAction = java.util.Objects.requireNonNull(removeAction, "removeAction");
        if (ChatMessage.isImageFile(file)) {
            thumbnail.setImage(new Image(file.toURI().toString(), 60, 60, true, true));
            thumbnail.setVisible(true);
            thumbnail.setManaged(true);
            fileType.setVisible(false);
            fileType.setManaged(false);
        } else {
            String extension = ChatMessage.getFileExtension(file).toUpperCase();
            fileType.setText(extension.isEmpty() ? "FILE" : extension);
        }
        String displayName = file.getName().length() > 12
                ? file.getName().substring(0, 9) + "..."
                : file.getName();
        fileName.setText(displayName);
        fileName.setTooltip(new Tooltip(file.getName()));
        root.setAccessibleText("附件 " + file.getName());
    }

    @FXML
    private void removeRequested() {
        removeAction.run();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        removeAction = () -> { };
        if (thumbnail != null) thumbnail.setImage(null);
    }

    boolean isClosed() {
        return closed;
    }
}
