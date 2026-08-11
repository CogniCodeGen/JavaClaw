package com.javaclaw.ui.javafx.interaction;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 确认弹窗的纯 JavaFX 状态，不持有服务或弹窗对象。 */
final class ConfirmDialogViewModel implements AutoCloseable {

    private final StringProperty description = new SimpleStringProperty("");
    private final StringProperty hint = new SimpleStringProperty("");
    private final StringProperty details = new SimpleStringProperty("");
    private final StringProperty keywordPrompt = new SimpleStringProperty("");
    private final StringProperty keywordInput = new SimpleStringProperty("");
    private final StringProperty expectedKeyword = new SimpleStringProperty("");
    private final BooleanProperty keywordRequired = new SimpleBooleanProperty(false);
    private final BooleanBinding valid = Bindings.createBooleanBinding(
            () -> !keywordRequired.get()
                    || expectedKeyword.get().equals(keywordInput.get().trim()),
            keywordRequired, expectedKeyword, keywordInput);

    void apply(ConfirmRequest request) {
        boolean doubleConfirm = request.kind() == ConfirmKind.DOUBLE_CONFIRM;
        description.set(request.description());
        expectedKeyword.set(request.keyword());
        keywordRequired.set(doubleConfirm);
        keywordPrompt.set(doubleConfirm ? "输入 " + request.keyword() : "");
        hint.set(hint(request, doubleConfirm));
        details.set("工具名：" + request.toolName()
                + "\n风险等级：" + request.riskLabel()
                + "\n超时：" + request.timeoutSeconds() + " 秒"
                + "\n托管场景：" + (request.managedTask() ? "是" : "否")
                + "\n\n---- 操作参数 ----\n" + request.description());
        keywordInput.set("");
    }

    private static String hint(ConfirmRequest request, boolean doubleConfirm) {
        if (!doubleConfirm) {
            return request.managedTask()
                    ? "选择「同意全部」后，本任务内后续高风险操作将自动放行。"
                    : "";
        }
        String base = "请输入关键词「" + request.keyword() + "」以启用允许按钮。";
        return request.managedTask()
                ? base + "选择「同意全部」后，本任务内后续高风险操作将自动放行。"
                : base;
    }

    StringProperty descriptionProperty() { return description; }
    StringProperty hintProperty() { return hint; }
    StringProperty detailsProperty() { return details; }
    StringProperty keywordPromptProperty() { return keywordPrompt; }
    StringProperty keywordInputProperty() { return keywordInput; }
    BooleanProperty keywordRequiredProperty() { return keywordRequired; }
    BooleanBinding validBinding() { return valid; }

    @Override
    public void close() {
        keywordInput.set("");
        expectedKeyword.set("");
        valid.dispose();
    }
}
