package com.javaclaw.desktop;

import java.util.Arrays;
import java.util.LinkedHashMap;

import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.model.ProviderInfo;

/** 原模型设置表单；凭据只写 SecretStore，读取和刷新只得到 configured/revision。 */
final class ProviderPane extends ManagedManagementPage {
    private final ListView<ProviderInfo> providers = ManagementForms.list(
            value -> DesktopPresentationMapper.provider(value.id()) + "\n" + readiness(value),
            "尚无云模型 Provider",
            "JavaClaw 4.0 首发支持 OpenAI、Anthropic 和 Google。");
    private final BorderPane detail = new BorderPane();

    ProviderPane(ManagementViewModel model) {
        super(model);
        setCenter(ManagementForms.split(providers, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command("刷新", UiActionKind.GHOST, model, () -> requestNavigation(this::reload))));
        guardSelection(providers, this::edit);
        detail.setCenter(
                ManagementForms.emptyState("☁", "选择云模型 Provider", "配置对话模型、Embedding 与加密凭据。未配置 Embedding 时知识库使用关键词检索。"));
        reload();
    }

    private void reload() {
        refreshing();
        String selectedId = providers.getSelectionModel().getSelectedItem() == null
                ? null
                : providers.getSelectionModel().getSelectedItem().id();
        model.execute(
                "读取云 Provider",
                sdk -> sdk.models().listProviders(),
                values -> {
                    providers.getItems().setAll(values);
                    var selected = values.stream()
                            .filter(value -> value.id().equals(selectedId))
                            .findFirst()
                            .orElse(values.isEmpty() ? null : values.getFirst());
                    if (selected != null) {
                        providers.getSelectionModel().select(selected);
                    }
                    ready();
                },
                ignored -> loadFailed());
    }

    private void edit(ProviderInfo original) {
        var modelName = ManagementForms.text(original.model(), "对话模型");
        var embedding = ManagementForms.text(original.embeddingModel(), "Embedding 模型（可选）");
        var base = ManagementForms.text(original.baseUrl(), "留空使用 Provider 官方地址");
        var secret = new PasswordField();
        secret.setPromptText("仅输入新的 API Key；不会显示已保存的值");
        var save = ManagementForms.command("保存模型配置", UiActionKind.PRIMARY, model, () -> {
            String selectedModel =
                    modelName.getText() == null ? "" : modelName.getText().strip();
            if (selectedModel.isEmpty()) {
                model.validationError("对话模型不能为空");
                modelName.requestFocus();
                return;
            }
            var configuration = new LinkedHashMap<String, String>();
            configuration.put("model", selectedModel);
            putWhenPresent(configuration, "embeddingModel", embedding.getText());
            putWhenPresent(configuration, "baseUrl", base.getText());
            model.execute(
                    "保存 Provider 配置",
                    sdk -> ProviderProfileSynchronizer.configure(sdk, original, configuration),
                    result -> {
                        saveSucceeded();
                        replaceProvider(original, result.provider());
                        model.profilesChanged();
                        if (result.complete()) {
                            String synchronizedProfiles = result.synchronizedProfiles() == 0
                                    ? ""
                                    : "，并同步 " + result.synchronizedProfiles() + " 个执行 Profile";
                            model.operationSucceeded("已保存 "
                                    + DesktopPresentationMapper.provider(
                                            result.provider().id())
                                    + " 模型配置"
                                    + synchronizedProfiles);
                        } else {
                            model.operationPartiallyFailed("Provider 模型已保存，已同步 "
                                    + result.synchronizedProfiles()
                                    + " 个 Profile；以下 Profile 因版本冲突或服务错误未更新，请刷新后重试："
                                    + String.join("、", result.failedProfiles()));
                        }
                    },
                    this::saveFailed);
        });
        var credential = ManagementForms.command("保存新凭据", model, () -> {
            char[] value = secret.getText().toCharArray();
            secret.clear();
            model.execute(
                    "保存凭据",
                    sdk -> sdk.models()
                            .setCredential(original.id(), value, ManagementViewModel.key("provider-credential"))
                            .whenComplete((result, failure) -> Arrays.fill(value, '\0')),
                    ignored -> reload());
        });
        var clear = ManagementForms.command("清除凭据", UiActionKind.DANGER, model, () -> {
            if (ManagementForms.confirm(this, model, "清除 Provider 凭据", "清除 " + original.id() + " 的持久凭据，不修改环境变量覆盖值。")) {
                model.execute(
                        "清除凭据",
                        sdk -> sdk.models()
                                .clearCredential(
                                        original.id(),
                                        original.credentialRevision(),
                                        ManagementViewModel.key("provider-clear")),
                        ignored -> reload());
            }
        });
        var fields = ManagementForms.form(
                ManagementForms.hint(
                        DesktopPresentationMapper.provider(original.id()) + " · 运行状态：" + readiness(original)),
                ManagementForms.section(
                        "模型与端点",
                        ManagementForms.field("对话模型", modelName),
                        ManagementForms.field("向量模型（Embedding）", embedding),
                        ManagementForms.field("API 基础地址（Base URL）", base),
                        ManagementForms.hint("OpenAI 兼容端点可填写 http(s)://host:port，裸地址会自动使用 /v1；需要鉴权时再保存 API Key。")),
                ManagementForms.independent(ManagementForms.section(
                        "加密凭据", ManagementForms.field("新凭据", secret), ManagementForms.actions(credential, clear))),
                ManagementForms.hint(
                        "保存对话模型时，会同步内置 Profile 和仍沿用旧默认值的同 Provider Profile；在“智能体”页明确使用其他模型的自定义 Profile 保持不变。环境变量仅为本进程临时覆盖。"));
        editSession(DesktopPresentationMapper.provider(original.id()), fields, save, this::reload);
        detail.setCenter(ManagementForms.editor(fields, save));
    }

    private void replaceProvider(ProviderInfo previous, ProviderInfo saved) {
        int index = providers.getItems().indexOf(previous);
        if (index < 0) {
            index = providers.getItems().stream().map(ProviderInfo::id).toList().indexOf(saved.id());
        }
        if (index >= 0) {
            providers.getItems().set(index, saved);
            providers.getSelectionModel().select(index);
        }
        edit(saved);
    }

    private static String readiness(ProviderInfo value) {
        if (value.credentialRevision() > 0) {
            return "凭据已配置";
        }
        return value.configured() ? "环境变量或免密端点已就绪" : "未就绪";
    }

    /** 可选 Provider 字段以“缺少键”表示恢复默认值；空字符串不能传给服务端并成为无效配置。 */
    private static void putWhenPresent(LinkedHashMap<String, String> configuration, String key, String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.isEmpty()) {
            configuration.put(key, normalized);
        }
    }
}
