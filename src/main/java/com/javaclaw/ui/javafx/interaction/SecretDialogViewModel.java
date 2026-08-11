package com.javaclaw.ui.javafx.interaction;

import com.javaclaw.api.interaction.SecretRequest;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 安全输入弹窗的短生命周期页面状态。 */
final class SecretDialogViewModel {

    private final StringProperty message = new SimpleStringProperty("");
    private final StringProperty secret = new SimpleStringProperty("");
    private int maxLength = 4096;

    void apply(SecretRequest request) {
        message.set(request.message());
        maxLength = request.maxLength();
        secret.set("");
    }

    void clamp() {
        String value = secret.get();
        if (value != null && value.length() > maxLength) {
            secret.set(value.substring(0, maxLength));
        }
    }

    char[] takeAndClear() {
        char[] value = secret.get() == null ? new char[0] : secret.get().toCharArray();
        secret.set("");
        return value;
    }

    void clear() { secret.set(""); }
    StringProperty messageProperty() { return message; }
    StringProperty secretProperty() { return secret; }
}
