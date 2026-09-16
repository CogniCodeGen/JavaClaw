package com.javaclaw.desktop.settings;

import javafx.scene.control.ComboBox;

import com.javaclaw.api.ProviderImageSupport;

/** 逐模型图片输入声明；目录发现与模型名称都不能替代用户的明确配置。 */
final class ProviderImageSupportField extends ComboBox<ProviderImageSupport> {
    ProviderImageSupportField() {
        getItems().setAll(ProviderImageSupport.values());
        ProviderSetupChoices.configure(this, ProviderImageSupportField::label);
        setValue(ProviderImageSupport.UNKNOWN);
        setAccessibleText("图片输入能力");
        getStyleClass().add("provider-image-support");
    }

    static String label(ProviderImageSupport support) {
        return switch (support) {
            case UNKNOWN -> "未知";
            case SUPPORTED -> "支持";
            case UNSUPPORTED -> "不支持";
        };
    }
}
