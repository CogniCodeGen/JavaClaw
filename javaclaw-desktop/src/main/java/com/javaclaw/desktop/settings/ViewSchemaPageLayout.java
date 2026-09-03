package com.javaclaw.desktop.settings;

import java.util.Objects;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** ViewSchema 设置页的平台壳布局；扩展只能提供 schema 与数据，不能注入 JavaFX 控件。 */
final class ViewSchemaPageLayout {
    private ViewSchemaPageLayout() {}

    static VBox create(
            String title,
            String description,
            ComboBox<ExtensionRpcContracts.ViewDocument> documents,
            ViewSchemaFeedbackPane body) {
        Label heading = new Label(Objects.requireNonNull(title, "title"));
        heading.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label(Objects.requireNonNull(description, "description"));
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        FormSection selector = new FormSection("扩展页面", "页面结构来自已启用扩展，控件、校验和危险确认均由平台实现。");
        selector.addField("页面", Objects.requireNonNull(documents, "documents"));
        VBox page = new VBox(12, heading, hint, selector, Objects.requireNonNull(body, "body"));
        VBox.setVgrow(body, Priority.ALWAYS);
        page.getStyleClass().add("platform-page");
        return page;
    }
}
