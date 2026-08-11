package com.javaclaw.ui.javafx.onboarding;

import com.javaclaw.application.onboarding.OnboardingApplicationService.Provider;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/** 加载 Provider 卡片 FXML；卡片生命周期归首次向导 Controller 所有。 */
public final class ProviderCardFactory {

    private static final URL VIEW = Objects.requireNonNull(
            ProviderCardFactory.class.getResource("/fxml/onboarding/provider-card.fxml"),
            "缺少 provider-card.fxml");

    private final SpringFxmlLoader loader;

    public ProviderCardFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    ProviderCardView create(
            Provider provider, boolean selected, Consumer<Provider> onSelected) {
        try {
            ViewHandle<VBox> handle = loader.load(VIEW);
            ProviderCardController controller = handle.controller(ProviderCardController.class);
            controller.configure(provider, selected, onSelected);
            return new ProviderCardView(handle.root(), controller, handle);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载 Provider 卡片失败", failure);
        }
    }
}
