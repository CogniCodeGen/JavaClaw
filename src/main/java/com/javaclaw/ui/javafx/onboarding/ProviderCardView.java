package com.javaclaw.ui.javafx.onboarding;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.VBox;

import java.util.Objects;

/** 已加载一次、可反复更新选中状态的 Provider 卡片。 */
record ProviderCardView(
        VBox root,
        ProviderCardController controller,
        ViewHandle<VBox> handle) implements AutoCloseable {

    ProviderCardView {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(handle, "handle");
    }

    @Override
    public void close() {
        handle.close();
    }
}
