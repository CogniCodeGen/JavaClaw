package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.time.Instant;

/** 收集宿主标准监听设置，并将证书密码交给应用层加密保存。 */
final class InferenceEndpointSettings {
    private InferenceEndpointSettings() { }
    record Fields(ToggleSwitch enabled, TextField address, TextField port, CheckBox tls, CheckBox insecureLan,
                  TextField keyStore, PasswordField password, TextField maxBytes, TextField timeout) { }
    static void save(InferenceManagementApplicationService service, InferenceSettingsUiActions ui,
                     InferenceCatalogPort.GatewayConfiguration current, Fields fields, Runnable reload) {
        char[] password = fields.password().getText().toCharArray(); fields.password().clear();
        var command = new InferenceCatalogPort.GatewayConfiguration(fields.enabled().isSelected(),
                SettingsFieldSupport.text(fields.address()), SettingsFieldSupport.integer(fields.port(), 1, 65535, "监听端口"),
                fields.tls().isSelected(), fields.insecureLan().isSelected(), SettingsFieldSupport.text(fields.keyStore()),
                current.encryptedKeyStorePassword(), SettingsFieldSupport.longInteger(fields.maxBytes(), 1024, 64L * 1024 * 1024, "请求体上限"),
                SettingsFieldSupport.integer(fields.timeout(), 1, 3600, "请求超时"), current.invocationLoggingEnabled(), Instant.now(), current.providerId());
        ui.run("保存推理 API 服务", context -> { service.saveGateway(command, password); return null; },
                ignored -> { reload.run(); ui.status("API 服务配置已原子重载"); });
    }
}
