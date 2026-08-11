package com.javaclaw.ui.javafx.onboarding;

import com.javaclaw.application.onboarding.OnboardingApplicationService;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 创建阻塞式首次向导窗口；静态布局全部来自 FXML。 */
public final class OnboardingViewFactory {

    public static final String UI_TEST_PROPERTY = "javaclaw.ui.test";
    private static final Logger log = LoggerFactory.getLogger(OnboardingViewFactory.class);
    private static final URL VIEW = Objects.requireNonNull(
            OnboardingViewFactory.class.getResource("/fxml/onboarding/onboarding-view.fxml"),
            "缺少 onboarding-view.fxml");

    private final OnboardingApplicationService useCases;
    private final SpringFxmlLoader loader;

    public OnboardingViewFactory(
            OnboardingApplicationService useCases,
            SpringFxmlLoader loader) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** 必须在 FX 线程调用；UI 测试模式不会修改持久化完成标记。 */
    public void showIfNeeded(Window owner) {
        if (Boolean.getBoolean(UI_TEST_PROPERTY)) {
            log.info("UI 测试模式：跳过首次使用向导（不修改持久化状态）");
            return;
        }
        if (!useCases.required()) return;
        OnboardingView view = create(owner);
        view.showAndWait();
    }

    OnboardingView create(Window owner) {
        ViewHandle<BorderPane> handle;
        try {
            handle = loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载首次向导 FXML 失败", failure);
        }
        try {
            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.setTitle("欢迎使用 JavaClaw");
            stage.setResizable(false);
            Scene scene = new Scene(handle.root(), 720, 520);
            URL css = OnboardingViewFactory.class.getResource("/css/chat.css");
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
            stage.setScene(scene);
            OnboardingView view = new OnboardingView(stage, handle);
            handle.controller(OnboardingController.class).configure(stage::close);
            return view;
        } catch (RuntimeException | Error failure) {
            try {
                handle.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
