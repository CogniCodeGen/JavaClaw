package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.CorrectionItem;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import java.util.Objects;
import java.util.function.Consumer;

/** 单条用户纠错及撤销、删除事件入口。 */
public final class MemoryCorrectionRowController {
    @FXML private Label claim;
    @FXML private Label time;
    @FXML private Label status;
    @FXML private Label type;
    @FXML private Label scope;
    @FXML private Label source;
    @FXML private Button revokeButton;
    private String id = "";
    private Consumer<String> revoke = ignored -> {};
    private Consumer<String> delete = ignored -> {};

    void configure(
            CorrectionItem item,
            Consumer<String> revoke,
            Consumer<String> delete) {
        id = item.id();
        this.revoke = Objects.requireNonNull(revoke, "revoke");
        this.delete = Objects.requireNonNull(delete, "delete");
        claim.setText(claim(item));
        time.setText("记录于 " + MemoryUiText.formatTime(item.timestamp()));
        configureStatus(item.status());
        type.setText("· " + type(item.type()));
        type.setVisible(!item.type().isBlank());
        type.setManaged(type.isVisible());
        scope.setText("· " + scope(item.scope()));
        scope.setVisible(!item.scope().isBlank());
        scope.setManaged(scope.isVisible());
        source.setText("· 原话：" + MemoryUiText.oneLine(item.sourceInput(), 60));
        source.setVisible(!item.sourceInput().isBlank());
        source.setManaged(source.isVisible());
        source.setTooltip(item.sourceInput().isBlank() ? null : new Tooltip(item.sourceInput()));
        revokeButton.setVisible(item.effective());
        revokeButton.setManaged(item.effective());
    }

    @FXML private void revokeRequested() { revoke.accept(id); }
    @FXML private void deleteRequested() { delete.accept(id); }

    private void configureStatus(String value) {
        status.getStyleClass().removeAll("jc-badge-ok", "jc-badge-amber", "jc-badge-soft");
        switch (value) {
            case "ACTIVE" -> { status.setText("生效中"); status.getStyleClass().add("jc-badge-ok"); }
            case "DISPUTED" -> { status.setText("待核验"); status.getStyleClass().add("jc-badge-amber"); }
            default -> { status.setText("已撤销"); status.getStyleClass().add("jc-badge-soft"); }
        }
    }

    private static String claim(CorrectionItem item) {
        if (item.wrongClaim().isBlank() && item.correctClaim().isBlank()) {
            return "（仅否定上一轮回答，未给出替代说法）";
        }
        if (item.wrongClaim().isBlank()) return "「" + item.correctClaim() + "」";
        if (item.correctClaim().isBlank()) return "「" + item.wrongClaim() + "」";
        return "「" + item.wrongClaim() + "」  →  「" + item.correctClaim() + "」";
    }

    private static String type(String value) {
        return switch (value) {
            case "FACT_REPLACEMENT" -> "事实更正";
            case "METHOD_CORRECTION" -> "做法更正";
            case "RETRACTION" -> "仅否定";
            default -> value;
        };
    }

    private static String scope(String value) {
        return switch (value) {
            case "USER" -> "个人偏好";
            case "PROJECT" -> "项目约定";
            case "GENERAL" -> "公共知识";
            default -> value;
        };
    }
}
