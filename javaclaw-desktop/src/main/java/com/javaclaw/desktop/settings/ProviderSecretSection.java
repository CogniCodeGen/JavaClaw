package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.SecretStatusField;

/** 连接分区中的一次性密钥编辑器；切换目标或离页时清空输入，提交字符数组在调用完成后清零。 */
final class ProviderSecretSection {
    private final Consumer<char[]> writer;
    private final PasswordField secret = new PasswordField();
    private final VBox editor = new VBox(8);
    private final SecretStatusField status;
    private final Label hint = new Label();
    private final VBox content;
    private SecretTarget target;
    private boolean keyRequired;
    private boolean configured;
    private boolean editing;

    ProviderSecretSection(PlatformComponentFactory components, Consumer<char[]> writer, Runnable clearer) {
        PlatformComponentFactory checked = Objects.requireNonNull(components, "components");
        this.writer = Objects.requireNonNull(writer, "writer");
        Runnable checkedClearer = Objects.requireNonNull(clearer, "clearer");
        status = new SecretStatusField(this::open, () -> {
            reset();
            checkedClearer.run();
        });
        secret.setPromptText("输入 API Key，仅用于本次写入");
        secret.setAccessibleText("API Key");
        secret.setId("providerSecretInput");
        Button apply = checked.action("写入密钥", ActionStyle.PRIMARY, ActionSize.COMPACT);
        apply.setId("providerSecretApply");
        apply.disableProperty().bind(secret.textProperty().isEmpty());
        apply.setOnAction(event -> submit());
        Button cancel = checked.action("取消", ActionStyle.GHOST, ActionSize.COMPACT);
        cancel.setOnAction(event -> reset());
        editor.getChildren().addAll(secret, new HBox(8, apply, cancel));
        hint.setId("providerSecretHint");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        hint.getStyleClass().add("sec-hint");
        content = new VBox(8, status, editor, hint);
        reset();
    }

    Node content() {
        return content;
    }

    void render(ProviderSettingsState state, boolean busy) {
        ProviderDraft draft = state.draft();
        SecretTarget next = new SecretTarget(
                state.selected().map(ProviderEndpoint::id).orElse(""),
                draft.adapter(),
                draft.baseUri(),
                draft.authentication());
        // 密钥只属于输入时的连接；后台状态重绘不改变目标时保留未提交内容。
        if (!next.equals(target)) {
            reset();
            target = next;
        }
        keyRequired = draft.authentication() == ProviderAuthentication.API_KEY;
        configured = state.credential().isPresent();
        boolean unavailable = state.selected()
                        .flatMap(endpoint -> endpoint.spec().credential())
                        .isPresent()
                && !configured;
        status.setConfigured(configured);
        if (unavailable) {
            status.setStatusText("引用缺失或密钥库不可用");
        }
        String blocked = blockedReason(state, busy, unavailable);
        status.setActionsDisabled(!blocked.isEmpty());
        editor.setDisable(!blocked.isEmpty());
        status.setVisible(keyRequired && (configured || unavailable));
        status.setManaged(status.isVisible());
        hint.setText(blocked.isEmpty() ? "密钥加密保存后不回显；提交或切换连接时清空输入。" : blocked);
        updateEditor();
    }

    private void open() {
        editing = true;
        updateEditor();
        secret.requestFocus();
    }

    void reset() {
        secret.clear();
        editing = false;
        updateEditor();
    }

    private void updateEditor() {
        editor.setVisible(keyRequired && (!configured || editing));
        editor.setManaged(editor.isVisible());
    }

    private void submit() {
        if (editor.isDisabled() || !editor.isVisible()) {
            return;
        }
        char[] value = secret.getText().toCharArray();
        reset();
        try {
            writer.accept(value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private static String blockedReason(ProviderSettingsState state, boolean busy, boolean unavailable) {
        if (state.draft().authentication() == ProviderAuthentication.NONE) {
            return "当前使用无鉴权，无需配置 API Key。";
        }
        if (busy) {
            return "正在处理模型服务，请稍候再写入密钥。";
        }
        if (state.selected().isEmpty() || state.dirty()) {
            return state.draft().lifecycle() == ProviderLifecycle.ACTIVE
                            && state.draft().credential().isEmpty()
                    ? "请先将状态设为「停用」并点击「保存模型服务」，再写入 API Key，完成后重新启用。"
                    : "请先点击「保存模型服务」保存连接配置，再在此填写并写入 API Key。";
        }
        if (state.selected().orElseThrow().lifecycle() == ProviderLifecycle.ARCHIVED) {
            return "已归档的模型服务不能修改密钥。";
        }
        return unavailable ? "密钥引用缺失或密钥库不可用，请检查密钥库后刷新。" : "";
    }

    /** 密钥输入绑定的连接身份；不包含秘密，只有身份变化才清空正在输入的内容。 */
    private record SecretTarget(
            String providerId, ProviderAdapter adapter, String baseUri, ProviderAuthentication authentication) {}
}
