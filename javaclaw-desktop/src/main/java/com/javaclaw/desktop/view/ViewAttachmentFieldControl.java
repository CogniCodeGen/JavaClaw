package com.javaclaw.desktop.view;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.extension.spi.ViewAttachmentPolicy;

/** 平台拥有的 Attachment 选择、上传、取消与摘要展示控件。 */
final class ViewAttachmentFieldControl extends VBox {
    private final ViewAttachmentPolicy policy;
    private final ViewInteractionHandler interactions;
    private final ObjectProperty<AttachmentRef> value = new SimpleObjectProperty<>();
    private final BooleanProperty pending = new SimpleBooleanProperty();
    private final ViewRequestEpoch requests = new ViewRequestEpoch();
    private final Label status = new Label();
    private final Button choose;
    private final Button cancel;
    private final Button clear;
    private CancellationSource active;

    ViewAttachmentFieldControl(
            String label,
            ViewAttachmentPolicy policy,
            Object initialValue,
            ViewInteractionHandler interactions,
            PlatformComponentFactory components) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(components, "components");
        value.set(parse(initialValue).map(this::requireAllowed).orElse(null));
        choose = components.action("选择并上传", ActionStyle.SOFT, ActionSize.COMPACT);
        cancel = components.action("取消", ActionStyle.GHOST, ActionSize.COMPACT);
        clear = components.action("清除", ActionStyle.GHOST, ActionSize.COMPACT);
        configure(label);
        renderValue();
    }

    ReadOnlyObjectProperty<AttachmentRef> valueProperty() {
        return value;
    }

    ReadOnlyBooleanProperty pendingProperty() {
        return pending;
    }

    Optional<AttachmentRef> value() {
        return Optional.ofNullable(value.get());
    }

    boolean pending() {
        return pending.get();
    }

    void upload(Path source) {
        cancelActive("已替换待上传文件");
        long epoch = requests.begin();
        CancellationSource cancellation = new CancellationSource();
        active = cancellation;
        pending.set(true);
        showStatus("正在上传并校验摘要…");
        updateButtons();
        try {
            interactions
                    .upload(new ViewAttachmentUploadRequest(source, policy, cancellation))
                    .whenComplete((attachment, failure) -> onFx(() -> complete(epoch, attachment, failure)));
        } catch (RuntimeException failure) {
            complete(epoch, null, failure);
        }
    }

    void cancelUpload() {
        cancelActive("用户取消 Attachment 上传");
        if (pending.get()) {
            pending.set(false);
            showStatus("上传已取消");
            updateButtons();
        }
    }

    private void configure(String label) {
        setSpacing(6);
        getStyleClass().add("platform-attachment-field");
        setAccessibleText(label);
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
        choose.setOnAction(event -> chooseFile());
        cancel.setOnAction(event -> cancelUpload());
        clear.setOnAction(event -> clear());
        HBox actions = new HBox(8, choose, cancel, clear);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(actions, status);
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 Attachment");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("允许的本地文件", "*.*"));
        File selected =
                chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (selected != null) {
            upload(selected.toPath());
        }
    }

    private void clear() {
        cancelActive("Attachment 已清除");
        pending.set(false);
        value.set(null);
        renderValue();
    }

    private void complete(long epoch, AttachmentRef attachment, Throwable failure) {
        if (!requests.isCurrent(epoch)) {
            return;
        }
        active = null;
        pending.set(false);
        if (failure == null) {
            try {
                value.set(requireAllowed(attachment));
                renderValue();
            } catch (RuntimeException validationFailure) {
                showStatus("上传失败：" + message(validationFailure));
                updateButtons();
            }
        } else if (unwrap(failure) instanceof TurnCancelledException) {
            showStatus("上传已取消");
            updateButtons();
        } else {
            showStatus("上传失败：" + message(unwrap(failure)));
            updateButtons();
        }
    }

    private AttachmentRef requireAllowed(AttachmentRef attachment) {
        AttachmentRef checked = Objects.requireNonNull(attachment, "attachment");
        if (!policy.accepts(checked.mediaType())
                || checked.sizeBytes() < 1
                || checked.sizeBytes() > policy.maximumBytes()) {
            throw new IllegalArgumentException("上传结果不符合 ViewSchema Attachment 策略");
        }
        return checked;
    }

    private void renderValue() {
        value().ifPresentOrElse(
                        attachment -> showStatus(attachment.fileName() + " · " + attachment.sizeBytes()
                                + " bytes · SHA-256 " + attachment.digest()),
                        () -> showStatus("未选择文件 · 最大 " + policy.maximumBytes() + " bytes · "
                                + String.join(", ", policy.acceptedMediaTypes())));
        updateButtons();
    }

    private void updateButtons() {
        choose.setDisable(pending.get());
        cancel.setDisable(!pending.get());
        clear.setDisable(pending.get() || value.get() == null);
    }

    private void showStatus(String text) {
        status.setText(text);
    }

    private void cancelActive(String reason) {
        requests.cancel();
        if (active != null) {
            active.cancel(reason);
            active = null;
        }
    }

    private static Optional<AttachmentRef> parse(Object source) {
        if (source == null) {
            return Optional.empty();
        }
        if (source instanceof AttachmentRef attachment) {
            return Optional.of(attachment);
        }
        if (source instanceof Map<?, ?> values) {
            return Optional.of(new AttachmentRef(
                    Objects.toString(values.get("digest"), ""),
                    Objects.toString(values.get("mediaType"), ""),
                    Objects.toString(values.get("fileName"), ""),
                    number(values.get("sizeBytes"))));
        }
        throw new IllegalArgumentException("Attachment 初值必须是 AttachmentRef 对象");
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(Objects.toString(value, ""));
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
