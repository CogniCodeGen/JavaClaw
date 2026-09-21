package com.javaclaw.desktop.settings;

import java.util.List;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderReasoningSummary;
import com.javaclaw.desktop.component.FormSection;

/** 根据明确选择的协议展示请求选项；隐藏字段保留草稿，但不会进入其他协议的领域配置。 */
final class ProviderSetupAdvancedOptions extends TitledPane {
    private final TextField timeout = new TextField("60");
    private final TextField retries = new TextField("0");
    private final TextField organization = new TextField();
    private final TextField project = new TextField();
    private final TextField apiVersion = new TextField();
    private final ComboBox<ProviderReasoningSummary> reasoning = new ComboBox<>();
    private final FormSection openAi = new FormSection("OpenAI 选项", "仅向 OpenAI 兼容协议发送以下可选参数。");
    private final FormSection google = new FormSection("Gemini 选项", "版本单独填写，API 根地址不包含版本。");
    private final FormSection responses = new FormSection("Responses 选项", "配置原生 Responses 的推理摘要。");
    private final FormSection.FieldHandle timeoutError;
    private final FormSection.FieldHandle retriesError;
    private final FormSection.FieldHandle apiVersionError;

    ProviderSetupAdvancedOptions() {
        setText("高级设置");
        setId("providerWizardAdvanced");
        setExpanded(false);
        timeout.setId("providerWizardTimeout");
        retries.setId("providerWizardRetries");
        organization.setId("providerWizardOrganization");
        project.setId("providerWizardProject");
        apiVersion.setId("providerWizardApiVersion");
        reasoning.setId("providerWizardReasoning");
        reasoning.setItems(FXCollections.observableArrayList(ProviderReasoningSummary.values()));
        FormSection request = new FormSection("请求设置", "目录读取和模型调用使用以下请求参数。");
        timeoutError = request.addRequiredField("超时（秒）", timeout, "1–600 秒");
        retriesError = request.addRequiredField("最大重试次数", retries, "0–10 次");
        openAi.addOptionalField("Organization", organization, null);
        openAi.addOptionalField("Project", project, null);
        apiVersionError = google.addOptionalField("API Version", apiVersion, "留空使用 v1beta");
        responses.addField("Reasoning Summary", reasoning);
        setContent(new VBox(8, request, openAi, google, responses));
    }

    void onChanged(Runnable listener) {
        for (TextField field : List.of(timeout, retries, organization, project, apiVersion)) {
            field.textProperty().addListener((ignored, before, value) -> listener.run());
        }
        reasoning.valueProperty().addListener((ignored, before, value) -> listener.run());
    }

    void seed(ProviderDraft draft) {
        timeout.setText(Integer.toString(draft.timeoutSeconds()));
        retries.setText(Integer.toString(draft.maximumRetries()));
        organization.setText(draft.organization());
        project.setText(draft.project());
        apiVersion.setText(draft.apiVersion());
        reasoning.setValue(draft.reasoningSummary());
        timeoutError.clearError();
        retriesError.clearError();
        apiVersionError.clearError();
    }

    void protocol(ProviderAdapter adapter) {
        visible(openAi, adapter == ProviderAdapter.OPENAI_COMPATIBLE || adapter == ProviderAdapter.OPENAI_RESPONSES);
        visible(google, adapter == ProviderAdapter.GOOGLE_GENAI);
        visible(responses, adapter == ProviderAdapter.OPENAI_RESPONSES);
    }

    void preset(ProviderPreset preset) {
        organization.clear();
        project.clear();
        apiVersion.setText(preset.apiVersion());
        reasoning.setValue(ProviderReasoningSummary.AUTO);
    }

    boolean validate(ProviderAdapter adapter) {
        timeoutError.updateError(integerInRange(timeout, 1, 600) ? "" : "请输入 1–600 之间的整数秒数。");
        retriesError.updateError(integerInRange(retries, 0, 10) ? "" : "请输入 0–10 之间的整数。");
        boolean validVersion = adapter != ProviderAdapter.GOOGLE_GENAI
                || apiVersion.getText().isBlank()
                || apiVersion.getText().strip().matches("v[0-9][A-Za-z0-9._-]{0,63}");
        apiVersionError.updateError(validVersion ? "" : "请输入单个 API 版本，例如 v1beta。");
        TextField first = timeoutError.hasError()
                ? timeout
                : retriesError.hasError() ? retries : apiVersionError.hasError() ? apiVersion : null;
        if (first != null) {
            setExpanded(true);
            first.requestFocus();
            return false;
        }
        return true;
    }

    int timeoutSeconds() {
        return Integer.parseInt(timeout.getText().strip());
    }

    int maximumRetries() {
        return Integer.parseInt(retries.getText().strip());
    }

    String organization() {
        return organization.getText();
    }

    String project() {
        return project.getText();
    }

    String apiVersion() {
        return apiVersion.getText();
    }

    ProviderReasoningSummary reasoningSummary() {
        return reasoning.getValue();
    }

    private static boolean integerInRange(TextField field, int min, int max) {
        try {
            int value = Integer.parseInt(field.getText().strip());
            return value >= min && value <= max;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }
}
