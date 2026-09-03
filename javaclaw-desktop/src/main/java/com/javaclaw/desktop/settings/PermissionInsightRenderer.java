package com.javaclaw.desktop.settings;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;

/** 权限版本历史和五层有效权限的无状态视图投影。 */
final class PermissionInsightRenderer {
    private PermissionInsightRenderer() {}

    static void renderHistory(VBox rows, Label message, PermissionHistoryState state) {
        rows.getChildren().clear();
        for (PermissionProfile profile : state.history()) {
            Label version = new Label("版本 " + profile.version()
                    + (state.reference()
                                    .map(reference -> reference.version() == profile.version())
                                    .orElse(false)
                            ? " · 当前"
                            : ""));
            version.getStyleClass().add("platform-detail-text");
            rows.getChildren().add(version);
        }
        state.diff().ifPresent(diff -> renderDiff(rows, diff));
        message.setText(state.message());
        message.getStyleClass().remove("platform-action-error");
        if (state.phase() == SettingsLoadState.ERROR) {
            message.getStyleClass().add("platform-action-error");
        }
    }

    static void renderPreview(VBox rows, EffectivePermissionPreview preview) {
        for (var layer : preview.layers()) {
            String source = layer.source()
                    .map(reference -> reference.id() + " 版本 " + reference.version())
                    .orElse("平台即时约束");
            String result = layer.denialReasons().isEmpty() ? "未进一步收窄" : String.join("；", layer.denialReasons());
            Label line = new Label(SettingsLabels.permissionLayer(layer.layer()) + " · "
                    + (layer.applied() ? source : "未提供") + " · " + result);
            line.setWrapText(true);
            line.getStyleClass().add("platform-detail-text");
            rows.getChildren().add(line);
        }
        renderDenials(rows, preview);
    }

    private static void renderDiff(VBox rows, PermissionProfileDiff diff) {
        String sections = diff.changedSections().isEmpty()
                ? "没有分区变化"
                : String.join(
                        "、",
                        diff.changedSections().stream()
                                .map(PermissionSettingsFormatting::section)
                                .sorted()
                                .toList());
        Label comparison = new Label(
                "版本 " + diff.before().version() + " → 版本 " + diff.after().version() + "：" + sections);
        comparison.setWrapText(true);
        comparison.getStyleClass().add("platform-detail-text");
        rows.getChildren().add(comparison);
    }

    private static void renderDenials(VBox rows, EffectivePermissionPreview preview) {
        if (preview.denialReasons().isEmpty()) {
            return;
        }
        Label denials = new Label("最终拒绝原因：" + String.join("；", preview.denialReasons()));
        denials.setWrapText(true);
        denials.getStyleClass().add("platform-action-error");
        rows.getChildren().add(denials);
    }
}
