package com.javaclaw.desktop.settings;

import java.util.function.Function;

import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;

/** 模型配置向导的有界选择控件；长名称允许收缩，完整内容保留在换行目录和提示中。 */
final class ProviderSetupChoices {
    private ProviderSetupChoices() {}

    static <T> void configure(ComboBox<T> choices, Function<T, String> label) {
        choices.setMinWidth(0);
        choices.setPrefWidth(240);
        choices.setMaxWidth(Double.MAX_VALUE);
        choices.setConverter(SettingsLabels.converter(label));
        choices.setCellFactory(ignored -> new ListCell<>() {
            {
                setPrefWidth(380);
                setMaxWidth(380);
                setWrapText(true);
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                String text = empty || item == null ? null : label.apply(item);
                setText(text);
                setTooltip(text == null ? null : tooltip(text));
            }
        });
        choices.valueProperty()
                .addListener((ignored, before, value) ->
                        choices.setTooltip(value == null ? null : tooltip(label.apply(value))));
    }

    static Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        return tooltip;
    }
}
