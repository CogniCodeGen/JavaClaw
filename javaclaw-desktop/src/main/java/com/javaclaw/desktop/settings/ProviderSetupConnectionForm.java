package com.javaclaw.desktop.settings;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.desktop.component.FormSection;

/** 连接草稿表单；已有密钥只显示存在性，改变请求目的地立即清除临时秘密。 */
final class ProviderSetupConnectionForm extends VBox {
    private final TextField address = new TextField();
    private final PasswordField secret = new PasswordField();
    private final TextField name = new TextField();
    private final ComboBox<ProviderPreset> preset = new ComboBox<>();
    private final ComboBox<ProviderPreset.BailianRegion> region = new ComboBox<>();
    private final ComboBox<ProviderAdapter> adapter = new ComboBox<>();
    private final ComboBox<ProviderAuthentication> authentication = new ComboBox<>();
    private final CheckBox replace = new CheckBox("替换密钥");
    private final CheckBox clear = new CheckBox("确认保存时清除已有密钥");
    private final Label saved = new Label();
    private final Label preview = new Label();
    private final VBox regionRow = new VBox(4);
    private final ProviderSetupAdvancedOptions advanced = new ProviderSetupAdvancedOptions();
    private final FormSection.FieldHandle addressError;
    private final FormSection.FieldHandle secretError;
    private final FormSection.FieldHandle nameError;
    private Runnable changed = () -> {};
    private Runnable destinationChanged = () -> {};
    private Runnable replacementCancelled = () -> {};
    private boolean seeding;
    private boolean bound;
    private boolean originalDefault;

    ProviderSetupConnectionForm() {
        super(8);
        configureControls();
        FormSection connection = new FormSection("连接设置", "获取模型只读取目录；点击保存后才写入配置。");
        connection.addField("服务商", new VBox(6, preset, regionRow));
        nameError = connection.addOptionalField("名称", name, null);
        connection.addField("接口协议", adapter);
        addressError = connection.addRequiredField("API 地址", address, null);
        TitledPane endpoints = new TitledPane("查看实际请求地址", preview);
        endpoints.setId("providerWizardEndpointPreview");
        endpoints.setExpanded(false);
        connection.addFullWidth(endpoints);
        connection.addField("鉴权方式", authentication);
        secretError = connection.addConditionalField("API Key", new VBox(4, saved, replace, secret, clear), null);
        getChildren().addAll(connection, advanced);
        bindChanges();
        seed(ProviderDraft.empty());
    }

    private void configureControls() {
        address.setId("providerWizardAddress");
        address.setPromptText("填写完整 API 根地址，如 https://api.example.com/v1");
        name.setId("providerWizardName");
        name.setPromptText("可选，留空使用服务地址作为名称");
        secret.setId("providerWizardSecret");
        secret.setPromptText("输入 API Key");
        replace.setId("providerWizardReplaceSecret");
        clear.setId("providerWizardClearSecret");
        saved.setId("providerWizardSecretStatus");
        preview.setId("providerWizardEndpointRoutes");
        for (Label label : List.of(saved, preview)) {
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.getStyleClass().add("settings-hint");
        }
        configureChoices();
    }

    private void configureChoices() {
        preset.setId("providerWizardPreset");
        preset.setItems(FXCollections.observableArrayList(ProviderPreset.values()));
        preset.setConverter(SettingsLabels.converter(ProviderPreset::label));
        region.setId("providerWizardRegion");
        region.setPromptText("请选择 API Key 所属地域");
        region.setItems(FXCollections.observableArrayList(ProviderPreset.BailianRegion.values()));
        region.setConverter(SettingsLabels.converter(ProviderPreset.BailianRegion::label));
        Label regionLabel = new Label("服务地域");
        regionLabel.setLabelFor(region);
        regionRow.getChildren().addAll(regionLabel, region);
        adapter.setId("providerWizardAdapter");
        adapter.setItems(FXCollections.observableArrayList(ProviderAdapter.values()));
        adapter.setConverter(SettingsLabels.converter(SettingsLabels::providerAdapter));
        authentication.setId("providerWizardAuthentication");
        authentication.setItems(FXCollections.observableArrayList(ProviderAuthentication.values()));
        authentication.setConverter(SettingsLabels.converter(
                value -> value == ProviderAuthentication.API_KEY ? "API Key" : "无鉴权（自定义兼容地址）"));
        for (ComboBox<?> choice : List.of(preset, region, adapter, authentication)) {
            choice.setMinWidth(0);
            choice.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private void bindChanges() {
        name.textProperty().addListener((ignored, before, value) -> edited());
        secret.textProperty().addListener((ignored, before, value) -> edited());
        address.textProperty().addListener((ignored, before, value) -> addressEdited());
        adapter.valueProperty().addListener((ignored, before, value) -> {
            if (value != ProviderAdapter.OPENAI_COMPATIBLE) {
                authentication.setValue(ProviderAuthentication.API_KEY);
            }
            controlsChanged();
            destinationEdited();
        });
        authentication.valueProperty().addListener((ignored, before, value) -> {
            controlsChanged();
            destinationEdited();
        });
        preset.valueProperty().addListener((ignored, before, value) -> applyPreset(value));
        region.valueProperty().addListener((ignored, before, value) -> applyRegion(value));
        replace.selectedProperty().addListener((ignored, before, value) -> {
            if (!value) {
                secret.clear();
                if (!seeding) {
                    replacementCancelled.run();
                    saved.setText("已配置");
                }
            }
            controlsChanged();
            edited();
        });
        clear.selectedProperty().addListener((ignored, before, value) -> edited());
        advanced.onChanged(() -> {
            updatePreview();
            edited();
        });
    }

    void onChanged(Runnable listener) {
        changed = listener;
    }

    void onDestinationChanged(Runnable listener) {
        destinationChanged = listener;
    }

    void onReplacementCancelled(Runnable listener) {
        replacementCancelled = listener;
    }

    void seed(ProviderDraft draft) {
        seeding = true;
        secret.clear();
        replace.setSelected(false);
        clear.setSelected(false);
        bound = draft.credential().isPresent();
        originalDefault = !draft.id().isEmpty() && draft.baseUri().isBlank();
        preset.setValue(ProviderPreset.matching(draft));
        region.setValue(
                preset.getValue() == ProviderPreset.BAILIAN
                        ? ProviderPreset.BailianRegion.matching(
                                draft.baseUri().strip().replaceFirst("/$", ""))
                        : null);
        name.setText(draft.displayName());
        address.setText(draft.baseUri());
        adapter.setValue(draft.adapter());
        authentication.setValue(draft.authentication());
        advanced.seed(draft);
        saved.setText(bound ? "已配置" : "密钥只在保存配置时写入本地凭据库。");
        addressError.clearError();
        secretError.clearError();
        nameError.clearError();
        controlsChanged();
        seeding = false;
        updatePreview();
    }

    private void applyPreset(ProviderPreset selected) {
        if (seeding || selected == null) {
            return;
        }
        seeding = true;
        originalDefault = false;
        name.setText(selected == ProviderPreset.CUSTOM ? "" : selected.label());
        adapter.setValue(selected.adapter());
        authentication.setValue(selected.authentication());
        region.setValue(null);
        address.setText(selected.baseUri());
        advanced.preset(selected);
        controlsChanged();
        seeding = false;
        destinationEdited();
    }

    private void applyRegion(ProviderPreset.BailianRegion selected) {
        if (seeding || preset.getValue() != ProviderPreset.BAILIAN || selected == null) {
            return;
        }
        seeding = true;
        address.setText(selected.baseUri());
        seeding = false;
        destinationEdited();
        if (selected == ProviderPreset.BailianRegion.CONSOLE) {
            address.requestFocus();
        }
    }

    private void controlsChanged() {
        boolean noAuth = authentication.getValue() == ProviderAuthentication.NONE;
        authentication.setDisable(adapter.getValue() != ProviderAdapter.OPENAI_COMPATIBLE);
        visible(regionRow, preset.getValue() == ProviderPreset.BAILIAN);
        visible(replace, bound && !noAuth);
        visible(clear, bound && noAuth);
        visible(secret, !noAuth && (!bound || replace.isSelected()));
        secret.setDisable(noAuth);
        advanced.protocol(adapter.getValue());
    }

    private void addressEdited() {
        if (!seeding
                && preset.getValue() == ProviderPreset.BAILIAN
                && region.getValue() != null
                && !address.getText()
                        .strip()
                        .replaceFirst("/$", "")
                        .equals(region.getValue().baseUri())) {
            // 手动修改为业务空间地址时保留原样，不根据地域猜测或拼接域名。
            seeding = true;
            region.setValue(ProviderPreset.BailianRegion.CONSOLE);
            seeding = false;
        }
        destinationEdited();
    }

    private void destinationEdited() {
        if (!seeding) {
            // 目的地变化必须同时使工作流内已转移的秘密失效，防止旧密钥发送到新服务。
            secret.clear();
            if (bound && authentication.getValue() == ProviderAuthentication.API_KEY) {
                replace.setSelected(true);
                saved.setText("连接地址或协议已更改，请重新填写密钥。");
            }
            destinationChanged.run();
            edited();
        }
        updatePreview();
    }

    ProviderDraft draft() {
        return new ProviderDraft(
                "",
                displayName(),
                adapter.getValue(),
                address.getText(),
                authentication.getValue(),
                List.of(),
                Optional.empty(),
                advanced.timeoutSeconds(),
                advanced.maximumRetries(),
                advanced.organization(),
                advanced.project(),
                advanced.apiVersion(),
                advanced.reasoningSummary(),
                ProviderLifecycle.DISABLED);
    }

    private String displayName() {
        if (!name.getText().isBlank()) {
            return name.getText().strip();
        }
        try {
            String host = URI.create(address.getText().strip()).getHost();
            return host == null ? SettingsLabels.providerAdapter(adapter.getValue()) : host;
        } catch (IllegalArgumentException invalid) {
            return SettingsLabels.providerAdapter(adapter.getValue());
        }
    }

    /** 保留工作流秘密时允许控件为空；失败只更新行内反馈，不消费任何秘密。 */
    boolean validate(boolean hasPreparedSecret) {
        nameError.updateError(name.getText().strip().length() > 1_000 ? "名称不能超过 1,000 个字符。" : "");
        addressError.updateError(addressProblem());
        secretError.updateError(secretProblem(hasPreparedSecret));
        if (nameError.hasError()) {
            name.requestFocus();
            return false;
        }
        if (addressError.hasError()) {
            address.requestFocus();
            return false;
        }
        if (secretError.hasError()) {
            if (authentication.getValue() == ProviderAuthentication.NONE) {
                clear.requestFocus();
            } else {
                focusSecret();
            }
            return false;
        }
        return advanced.validate(adapter.getValue());
    }

    private String secretProblem(boolean hasPreparedSecret) {
        if (authentication.getValue() == ProviderAuthentication.NONE) {
            return bound && !clear.isSelected() ? "切换无鉴权将清除已有密钥，请先明确确认。" : "";
        }
        boolean needsSecret = (!bound || replace.isSelected()) && !hasPreparedSecret && !hasSecretInput();
        return needsSecret ? "请填写 API Key。" : "";
    }

    private String addressProblem() {
        if (preset.getValue() == ProviderPreset.BAILIAN && region.getValue() == null) {
            return "请选择 API Key 所属地域；其他地域或业务空间请填写控制台完整 Base URL。";
        }
        if (address.getText().isBlank()) {
            return originalDefault && authentication.getValue() == ProviderAuthentication.API_KEY
                    ? ""
                    : "请填写完整 API 根地址。";
        }
        try {
            ProviderEndpointSpec.validateBaseUri(
                    adapter.getValue(),
                    authentication.getValue(),
                    URI.create(address.getText().strip()));
            return "";
        } catch (IllegalArgumentException invalid) {
            return "请输入有效 API 根地址，不包含密钥、查询参数或模型调用路径；API Key 仅允许 HTTPS 或本机 HTTP。";
        }
    }

    private void updatePreview() {
        if (seeding) {
            return;
        }
        try {
            preview.setText(ProviderEndpointPreview.describe(draft()));
        } catch (IllegalArgumentException invalid) {
            preview.setText("填写有效地址和请求参数后可查看实际请求地址。");
        }
    }

    boolean hasSecretInput() {
        return !secret.getText().isBlank();
    }

    char[] takeSecret() {
        char[] value = secret.getText().toCharArray();
        secret.clear();
        return value;
    }

    boolean replacementRequested() {
        return replace.isSelected();
    }

    boolean clearingConfirmed() {
        return clear.isSelected();
    }

    void prepared() {
        if (authentication.getValue() == ProviderAuthentication.NONE) {
            saved.setText("连接草稿已准备，尚未保存。");
        } else {
            saved.setText(bound && !replace.isSelected() ? "已配置，沿用已存密钥。" : "连接草稿已准备，尚未保存。临时密钥保留在当前编辑会话中。");
        }
        secretError.clearError();
    }

    void clearSecret() {
        secret.clear();
        saved.setText(bound ? "已配置；替换密钥需重新输入。" : "密钥只在保存配置时写入本地凭据库。");
    }

    void focusSecret() {
        if (bound) {
            replace.setSelected(true);
        }
        controlsChanged();
        secret.requestFocus();
    }

    private void edited() {
        if (!seeding) {
            changed.run();
        }
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }
}
