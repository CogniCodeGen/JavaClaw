package com.javaclaw.chat;

import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.LinkRange;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.RenderedMarkdown;
import com.javaclaw.platform.desktop.ExternalLinkOpener;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.TextPos;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 把不可变 Markdown 渲染结果转换为可选择的 JavaFX 只读视图。 */
final class MarkdownRenderedViewFactory {

    private MarkdownRenderedViewFactory() {
    }

    static RichTextArea create(
            RenderedMarkdown rendered,
            double prefWidth,
            Supplier<String> rawMarkdown,
            ExternalLinkOpener links) {
        Objects.requireNonNull(rendered, "rendered");
        RichTextArea view = new RichTextArea(rendered.model());
        view.setEditable(false);
        view.setWrapText(true);
        view.setUseContentHeight(true);
        view.setFocusTraversable(false);
        view.setPrefWidth(prefWidth);
        view.getStyleClass().add("md-bubble");
        view.setContextMenu(contextMenu(view::copy, view::selectAll, rawMarkdown));

        List<LinkRange> ranges = rendered.links();
        view.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() != MouseButton.PRIMARY
                    || event.getClickCount() != 1
                    || !event.isStillSincePress()) return;
            LinkRange link = linkAt(view, ranges, event.getX(), event.getY());
            if (link != null) links.open(link.url());
        });
        view.addEventHandler(MouseEvent.MOUSE_MOVED, event ->
                view.setCursor(linkAt(view, ranges, event.getX(), event.getY()) != null
                        ? Cursor.HAND : Cursor.TEXT));
        return view;
    }

    static ContextMenu contextMenu(
            Runnable copySelection, Runnable selectAll, Supplier<String> rawMarkdown) {
        ContextMenu menu = new ContextMenu();
        MenuItem copy = new MenuItem("复制");
        copy.setOnAction(event -> copySelection.run());
        MenuItem select = new MenuItem("全选");
        select.setOnAction(event -> selectAll.run());
        MenuItem copyRaw = new MenuItem("复制原文 (Markdown)");
        copyRaw.setOnAction(event -> {
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString(rawMarkdown.get());
            Clipboard.getSystemClipboard().setContent(clipboard);
        });
        menu.getItems().addAll(copy, select, new SeparatorMenuItem(), copyRaw);
        return menu;
    }

    private static LinkRange linkAt(
            RichTextArea view, List<LinkRange> links, double x, double y) {
        if (links.isEmpty()) return null;
        TextPos position = view.getTextPosition(x, y);
        if (position == null) return null;
        for (LinkRange link : links) {
            if (position.index() == link.paragraphIndex()
                    && position.offset() >= link.startOffset()
                    && position.offset() < link.endOffset()) {
                return link;
            }
        }
        return null;
    }
}
