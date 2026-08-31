package com.javaclaw.desktop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;

import com.javaclaw.sdk.model.ArtifactItemContent;
import com.javaclaw.sdk.model.AttachmentContent;
import com.javaclaw.sdk.model.ImageItemContent;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.TurnInput;
import com.javaclaw.sdk.model.UserMessageItemContent;

/** SDK 附件与领域产物查看器；复用旧 modal/image-viewer 样式，不打开模型给出的本地路径或外部 URI。 */
final class ArtifactViewer {
    private ArtifactViewer() {}

    static void show(Node owner, ManagementViewModel model, ItemInfo item) {
        var dialog = new Dialog<Void>();
        dialog.setTitle("内容与附件 · "
                + DesktopPresentationMapper.status(
                        item.content() == null ? item.state() : item.content().kind()));
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        var raw = ManagementForms.area(ItemPresenter.text(item), 20);
        raw.setEditable(false);
        var content = new VBox(
                12, ManagementForms.hint("Item " + item.id() + " · " + DesktopPresentationMapper.status(item.state())));
        List<TurnInput.Attachment> attachments = references(item);
        for (var reference : attachments) {
            var preview = new VBox(8);
            var load = ManagementForms.command(
                    "预览图片",
                    model,
                    () -> model.execute(
                            "读取图片附件",
                            sdk -> sdk.attachments()
                                    .readContent(reference.sha256(), 8 * 1024 * 1024)
                                    .thenApplyAsync(ArtifactViewer::previewImage, Thread::startVirtualThread),
                            data -> {
                                if (!dialog.isShowing()) {
                                    return;
                                }
                                var image = new Image(new ByteArrayInputStream(data));
                                if (image.isError()) {
                                    throw new IllegalArgumentException("图片解码失败，请下载后检查格式");
                                }
                                var view = new ImageView(image);
                                view.setPreserveRatio(true);
                                var scale = new Slider(0.1, 2, Math.min(1, 680 / image.getWidth()));
                                view.fitWidthProperty()
                                        .bind(scale.valueProperty().multiply(image.getWidth()));
                                var scroll = new ScrollPane(view);
                                scroll.setPannable(true);
                                scroll.setPrefViewportHeight(420);
                                scroll.getStyleClass().add("image-viewer-viewport");
                                preview.getChildren().setAll(scroll, ManagementForms.field("缩放 / 拖动查看", scale));
                            }));
            load.setVisible(reference.mediaType().startsWith("image/"));
            load.setManaged(load.isVisible());
            var download = ManagementForms.command("下载…", model, () -> {
                var selected = model.dialogs()
                        .chooseSaveFile(dialog.getDialogPane(), "保存附件", safeName(reference.displayName()), List.of())
                        .orElse(null);
                if (selected == null) {
                    return;
                }
                boolean replace = Files.exists(selected);
                if (replace && !ManagementForms.confirm(owner, model, "覆盖文件", "将替换所选文件：" + selected.getFileName())) {
                    return;
                }
                model.execute(
                        "下载附件",
                        sdk -> sdk.attachments().download(reference.sha256(), selected, replace),
                        path -> preview.getChildren().add(ManagementForms.hint("已保存：" + path)));
            });
            content.getChildren()
                    .addAll(
                            ManagementForms.hint(reference.displayName() + " · " + reference.mediaType() + "\nSHA-256 "
                                    + reference.sha256()),
                            ManagementForms.actions(load, download),
                            preview);
        }
        if (attachments.isEmpty() && item.content() instanceof ImageItemContent) {
            content.getChildren().add(ManagementForms.hint("不是服务器内容寻址附件；为避免外部访问，不自动打开此 URI。"));
        }
        var markdown = new MarkdownView();
        markdown.show(ItemPresenter.text(item), true, uri -> openLink(owner, model, uri));
        var tabs = new TabPane(new Tab("内容", ManagementForms.scroll(markdown)), new Tab("原文", raw));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        content.getChildren().add(tabs);
        var status = new Label();
        status.setWrapText(true);
        status.textProperty().bind(model.statusProperty());
        content.getChildren().add(status);
        dialog.getDialogPane().setContent(ManagementForms.scroll(ManagementForms.form(content)));
        dialog.getDialogPane().setPrefWidth(860);
        ManagementForms.style(owner, dialog);
        dialog.setOnHidden(ignored -> {
            status.textProperty().unbind();
            markdown.clear();
        });
        dialog.show();
    }

    static List<TurnInput.Attachment> references(ItemInfo item) {
        if (item.content() instanceof UserMessageItemContent user) {
            return user.attachments();
        }
        String uri;
        String name;
        String type;
        if (item.content() instanceof ImageItemContent image) {
            uri = image.uri();
            name = "image.png";
            type = "image/png";
        } else if (item.content() instanceof ArtifactItemContent artifact
                && artifact.category().equals("browserAttachment")) {
            uri = artifact.content();
            name = artifact.name();
            type = name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "application/pdf" : "application/octet-stream";
        } else {
            return List.of();
        }
        String prefix = "attachment:sha256:";
        return uri.startsWith(prefix) && uri.substring(prefix.length()).matches("[0-9a-f]{64}")
                ? List.of(new TurnInput.Attachment(uri.substring(prefix.length()), type, name))
                : List.of();
    }

    static void openLink(Node owner, ManagementViewModel model, java.net.URI uri) {
        if (MarkdownDocument.safeLink(uri.toString()) != null
                && ManagementForms.confirm(owner, model, "打开外部链接", "将在系统浏览器中打开：\n" + uri + "\n页面内容不代表新的任务授权。")) {
            model.openAuthorization(uri);
        }
    }

    static byte[] previewImage(AttachmentContent content) {
        // 只处理有界位图。先读取尺寸并按采样缩小，防止压缩炸弹或超大图片耗尽 UI 堆。
        if (!List.of("image/png", "image/jpeg", "image/gif")
                .contains(content.attachment().mediaType())) {
            throw new IllegalArgumentException("该图片格式不提供内置预览，请下载后查看");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content.bytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("附件不是可识别的图片");
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1
                        || height < 1
                        || width > 32_768
                        || height > 32_768
                        || (long) width * height > 40_000_000) {
                    throw new IllegalArgumentException("图片尺寸超过安全预览上限");
                }
                var parameters = reader.getDefaultReadParam();
                int sampling = Math.max(1, (Math.max(width, height) + 2_047) / 2_048);
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                var image = reader.read(0, parameters);
                var output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("图片无法安全解码", invalid);
        }
    }

    private static String safeName(String value) {
        String name = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return name.isBlank() || name.equals(".") || name.equals("..")
                ? "attachment"
                : name.substring(0, Math.min(180, name.length()));
    }
}
