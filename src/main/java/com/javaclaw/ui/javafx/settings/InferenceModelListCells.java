package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.HuggingFaceModelCatalogPort;
import com.javaclaw.inference.api.InferenceModelAsset;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/** Compact, two-line model rows shared by the Deliverance catalog and service console. */
final class InferenceModelListCells {
    private InferenceModelListCells() { }

    static Callback<ListView<InferenceSettingsChoice<HuggingFaceModelCatalogPort.ModelSummary>>,
            ListCell<InferenceSettingsChoice<HuggingFaceModelCatalogPort.ModelSummary>>> online() {
        return online(ignored -> "用途待确认");
    }

    static Callback<ListView<InferenceSettingsChoice<HuggingFaceModelCatalogPort.ModelSummary>>,
            ListCell<InferenceSettingsChoice<HuggingFaceModelCatalogPort.ModelSummary>>> online(
            Function<String, String> purpose) {
        return ignored -> new ListCell<>() {
            @Override
            protected void updateItem(
                    InferenceSettingsChoice<HuggingFaceModelCatalogPort.ModelSummary> item,
                    boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    clear(this);
                    return;
                }
                var model = item.value();
                setRow(this, row("HF", model.displayName(), purpose.apply(model.modelType())
                                + " · " + model.modelType() + " · " + model.repository(),
                        model.quantizationType(), InferenceModelPresentation.bytes(
                                model.quantizedSizeBytes()), relative(model.lastModified()),
                        model.gated()));
            }
        };
    }

    static Callback<ListView<InferenceSettingsChoice<UUID>>, ListCell<InferenceSettingsChoice<UUID>>>
            local(Function<UUID, InferenceModelAsset> assets, Predicate<UUID> loaded) {
        return ignored -> new ListCell<>() {
            @Override
            protected void updateItem(InferenceSettingsChoice<UUID> item, boolean empty) {
                super.updateItem(item, empty);
                InferenceModelAsset asset = empty || item == null ? null : assets.apply(item.value());
                if (asset == null) {
                    clear(this);
                    return;
                }
                boolean running = loaded.test(asset.id());
                long bytes = asset.artifactMetadata().quantizedSizeBytes() > 0
                        ? asset.artifactMetadata().quantizedSizeBytes() : asset.sizeBytes();
                String source = asset.source() == InferenceModelAsset.Source.HUGGING_FACE
                        ? "Hugging Face" : "本地导入";
                setRow(this, row("◈", asset.displayName(), source + " · " + relative(asset.createdAt()),
                        running ? "READY" : "LOCAL", asset.quantizationType(),
                        InferenceModelPresentation.bytes(bytes), false));
            }
        };
    }

    static <T> Callback<ListView<InferenceSettingsChoice<T>>, ListCell<InferenceSettingsChoice<T>>>
            service() {
        return ignored -> new ListCell<>() {
            @Override
            protected void updateItem(InferenceSettingsChoice<T> item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    clear(this);
                    return;
                }
                String label = item.label();
                int split = label.indexOf("  ");
                String state = split < 0 ? "MODEL" : label.substring(0, split).strip();
                String[] details = (split < 0 ? label : label.substring(split + 2)).split(" · ", 3);
                String title = details.length == 0 ? label : details[0];
                String meta = details.length < 2 ? "" : details[1]
                        + (details.length < 3 ? "" : " · " + details[2]);
                setRow(this, row("●", title, meta, state, "OpenAI", "可调用", false));
            }
        };
    }

    private static Node row(String glyphText, String titleText, String metaText,
                            String primaryTag, String secondaryTag, String trailingText,
                            boolean restricted) {
        Label glyph = label(glyphText, "service-plugin-model-glyph");
        Label title = label(titleText, "service-plugin-model-cell-title");
        title.setMaxWidth(Double.MAX_VALUE);
        Label meta = label(metaText, "service-plugin-model-cell-meta");
        meta.setMaxWidth(Double.MAX_VALUE);
        VBox body = new VBox(3, title, meta);
        HBox.setHgrow(body, Priority.ALWAYS);
        Label tag = label(primaryTag, "service-plugin-model-tag");
        Label tag2 = label(secondaryTag, "service-plugin-model-tag-muted");
        Label trailing = label(trailingText, "service-plugin-model-cell-trailing");
        VBox facts = new VBox(4, new HBox(4, tag, tag2), trailing);
        facts.setAlignment(Pos.CENTER_RIGHT);
        if (restricted) facts.getStyleClass().add("service-plugin-model-restricted");
        HBox row = new HBox(10, glyph, body, facts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("service-plugin-model-cell");
        return row;
    }

    private static Label label(String value, String style) {
        Label label = new Label(value == null ? "" : value);
        label.getStyleClass().add(style);
        return label;
    }

    private static void setRow(ListCell<?> cell, Node row) {
        unbindGraphic(cell.getGraphic());
        cell.setText(null);
        cell.setGraphic(row);
        if (row instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
            region.prefWidthProperty().bind(cell.widthProperty().subtract(22));
        }
        if (!cell.getStyleClass().contains("service-plugin-model-list-cell")) {
            cell.getStyleClass().add("service-plugin-model-list-cell");
        }
    }

    private static void clear(ListCell<?> cell) {
        unbindGraphic(cell.getGraphic());
        cell.setText(null);
        cell.setGraphic(null);
    }

    private static void unbindGraphic(Node graphic) {
        if (graphic instanceof Region region && region.prefWidthProperty().isBound()) {
            region.prefWidthProperty().unbind();
        }
    }

    private static String relative(Instant value) {
        if (value == null || Instant.EPOCH.equals(value)) return "更新时间未知";
        long days = Math.max(0, Duration.between(value, Instant.now()).toDays());
        if (days == 0) return "今天更新";
        if (days == 1) return "昨天更新";
        if (days < 30) return days + " 天前";
        long months = Math.max(1, days / 30);
        return months < 12 ? months + " 个月前" : Math.max(1, months / 12) + " 年前";
    }
}
