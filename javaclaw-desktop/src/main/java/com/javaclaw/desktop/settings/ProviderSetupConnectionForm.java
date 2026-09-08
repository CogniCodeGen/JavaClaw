package com.javaclaw.desktop.settings;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderReasoningSummary;
import com.javaclaw.desktop.component.FormSection;

/** 连接步骤只常驻服务地址与密钥，高级设置明确选择协议，不根据地址猜测协议。 */
final class ProviderSetupConnectionForm extends VBox {
    private final TextField address = new TextField();
    private final PasswordField secret = new PasswordField();
    private final TextField name = new TextField();
    private final TextField timeout = new TextField("60");
    private final TextField retries = new TextField("0");
    private final ComboBox<ProviderAdapter> adapter = new ComboBox<>();
    private final ComboBox<ProviderAuthentication> authentication = new ComboBox<>();
    private final Label saved = new Label();
    private final TitledPane advanced;

    ProviderSetupConnectionForm() {
        super(12);
        address.setId("providerWizardAddress");
        address.setPromptText("https://api.example.com/v1");
        secret.setId("providerWizardSecret");
        secret.setPromptText("输入 API Key，仅保存到本地凭据库");
        adapter.setItems(FXCollections.observableArrayList(ProviderAdapter.values()));
        adapter.setConverter(SettingsLabels.converter(SettingsLabels::providerAdapter));
        adapter.setValue(ProviderAdapter.OPENAI_COMPATIBLE);
        authentication.setItems(FXCollections.observableArrayList(ProviderAuthentication.values()));
        authentication.setConverter(SettingsLabels.converter(
                value -> value == ProviderAuthentication.API_KEY ? "API Key" : "无鉴权（仅自定义兼容地址）"));
        authentication.setValue(ProviderAuthentication.API_KEY);
        name.setPromptText("留空使用服务地址作为名称");
        FormSection connection = new FormSection("连接服务", "默认接入自定义 OpenAI 兼容接口。");
        connection.addField("服务地址", address);
        connection.addField("API Key", secret);
        connection.addFullWidth(saved);
        FormSection options = new FormSection("高级设置", "选择服务实际支持的协议；目录读取不会执行推理。");
        options.addField("连接名称", name);
        options.addField("接口协议", adapter);
        options.addField("鉴权方式", authentication);
        options.addField("超时（秒）", timeout);
        options.addField("最大重试次数", retries);
        advanced = new TitledPane("高级设置", options);
        advanced.setExpanded(false);
        getChildren().addAll(connection, advanced);
        adapter.valueProperty().addListener((ignored, before, value) -> {
            boolean compatible = value == ProviderAdapter.OPENAI_COMPATIBLE;
            authentication.setDisable(!compatible);
            if (!compatible) {
                authentication.setValue(ProviderAuthentication.API_KEY);
            }
        });
        authentication
                .valueProperty()
                .addListener((ignored, before, value) -> secret.setDisable(value == ProviderAuthentication.NONE));
    }

    ProviderDraft draft() {
        String displayName = name.getText().strip();
        if (displayName.isEmpty()) {
            displayName = address.getText().isBlank()
                    ? SettingsLabels.providerAdapter(adapter.getValue())
                    : URI.create(address.getText().strip()).getHost();
        }
        return new ProviderDraft(
                "",
                displayName,
                adapter.getValue(),
                address.getText(),
                authentication.getValue(),
                List.of(),
                Optional.empty(),
                Integer.parseInt(timeout.getText().strip()),
                Integer.parseInt(retries.getText().strip()),
                "",
                "",
                "",
                ProviderReasoningSummary.AUTO,
                ProviderLifecycle.DISABLED);
    }

    char[] secret() {
        return secret.getText().toCharArray();
    }

    void connectionSaved(boolean credentialSaved) {
        address.setDisable(true);
        advanced.setDisable(true);
        if (credentialSaved || authentication.getValue() == ProviderAuthentication.NONE) {
            secret.clear();
            secret.setDisable(true);
            saved.setText(credentialSaved ? "连接和 API Key 已保存" : "连接已保存，无需 API Key");
        } else {
            saved.setText("连接已保存，请重试保存 API Key");
        }
    }

    void clearSecret() {
        secret.clear();
    }
}
