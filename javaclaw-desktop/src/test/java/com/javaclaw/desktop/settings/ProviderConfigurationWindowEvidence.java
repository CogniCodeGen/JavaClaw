package com.javaclaw.desktop.settings;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.PixelFormat;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformStylesheets;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

/** 固定数据的模型服务列表和两步窗口截图入口；不访问模型、服务器或凭据库。 */
public final class ProviderConfigurationWindowEvidence {
    private ProviderConfigurationWindowEvidence() {}

    /**
     * 输出窄窗口及常规窗口的管理页和配置状态截图。
     *
     * @param arguments 不读取；输出目录由 javaclaw.provider.evidence 指定
     */
    public static void main(String[] arguments) {
        try {
            FxTestSupport.run(() -> {
                for (int width : List.of(880, 1040)) {
                    captureManagement(width, false);
                    captureManagement(width, true);
                    captureWizard(width, false);
                    captureWizard(width, true);
                }
            });
        } finally {
            Platform.exit();
        }
    }

    private static void captureManagement(int width, boolean empty) {
        var gateway = new ProviderConfigurationTestGateway();
        gateway.providers.clear();
        if (!empty) {
            gateway.providers.add(exampleProvider("example-primary", "示例兼容服务", ProviderLifecycle.ACTIVE));
            gateway.providers.add(exampleProvider("example-local", "本地开发服务", ProviderLifecycle.DISABLED));
        }
        var page = new ProviderSettingsPage(gateway);
        BorderPane root = new BorderPane();
        if (page.ownsViewport()) {
            root.setCenter(page.content());
        } else {
            ScrollPane scroll = new ScrollPane(page.content());
            scroll.setFitToWidth(true);
            root.setCenter(scroll);
        }
        root.setBottom(page.actionContent().orElseThrow());
        Stage window = show(root, width, width == 880 ? 620 : 720);
        try {
            page.activate();
            capture(root, "provider-list-" + (empty ? "empty-" : "normal-") + width + ".png");
        } finally {
            page.dispose();
            window.hide();
        }
    }

    private static ProviderEndpoint exampleProvider(String id, String name, ProviderLifecycle lifecycle) {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                name,
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("https://models.example.test/v1")),
                ProviderAuthentication.NONE,
                List.of(
                        new ProviderModelSpec(
                                "chat-general", "通用对话模型", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty()),
                        new ProviderModelSpec(
                                "embedding-documents",
                                "文档向量模型",
                                Set.of(ProviderModelPurpose.EMBEDDING),
                                OptionalInt.of(1024))),
                Optional.empty(),
                java.time.Duration.ofSeconds(60),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        return new ProviderEndpoint(id, 1, lifecycle, spec, Instant.EPOCH, Instant.EPOCH);
    }

    private static void captureWizard(int width, boolean failure) {
        var gateway = new ProviderConfigurationTestGateway();
        gateway.candidates = List.of(
                new ProviderModelDiscoveryCandidate(
                        "vendor/chat-latest", "通用对话模型", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty()),
                new ProviderModelDiscoveryCandidate(
                        "vendor/embedding-v3", "文档向量模型", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.of(1024)),
                new ProviderModelDiscoveryCandidate("opaque-model", "需要明确用途的模型", Set.of(), OptionalInt.empty()));
        if (failure) {
            gateway.previewResponses.add(
                    CompletableFuture.failedFuture(new IllegalStateException("此服务未提供模型目录，请手动输入模型 ID。")));
            gateway.saveResponses.add(CompletableFuture.failedFuture(new RemoteRpcException(
                    new JsonRpcError(ProtocolErrorCode.REVISION_CONFLICT, "服务配置已更新，请重新打开后重试。", Optional.empty()))));
        }
        Stage owner = show(new BorderPane(), width, width == 880 ? 620 : 720);
        ProviderSetupWizard.configure(owner, gateway, Optional.empty(), 0, ignored -> {});
        Stage window = ProviderSetupWizardFxTest.window();
        try {
            Parent root = window.getScene().getRoot();
            window.setWidth(660);
            window.setHeight(width == 880 ? 540 : 620);
            capture(root, "provider-connection-" + width + ".png");
            ProviderSetupWizardFxTest.connect(root);
            if (failure) {
                ProviderSetupWizardFxTest.manual(root, "my-selected-model");
            } else {
                ProviderSetupWizardFxTest.modelChoice(root, "vendor/chat-latest")
                        .fire();
            }
            capture(root, "provider-models-" + (failure ? "error-" : "normal-") + width + ".png");
            ProviderSetupWizardFxTest.button(root, "providerWizardContinue").fire();
            capture(root, "provider-save-" + (failure ? "failure-" : "success-") + width + ".png");
        } finally {
            window.hide();
            owner.hide();
        }
    }

    private static Stage show(Parent root, int width, int height) {
        Stage stage = new Stage();
        stage.setScene(new Scene(root, width, height));
        PlatformStylesheets.applyTo(root);
        stage.show();
        return stage;
    }

    private static void capture(Parent root, String name) {
        String directory = System.getProperty("javaclaw.provider.evidence", "");
        if (directory.isEmpty()) {
            return;
        }
        root.applyCss();
        root.layout();
        Object key = new Object();
        PauseTransition pulse = new PauseTransition(Duration.millis(80));
        pulse.setOnFinished(ignored -> Platform.exitNestedEventLoop(key, null));
        pulse.play();
        Platform.enterNestedEventLoop(key);
        var snapshot = root.getScene().snapshot(null);
        int width = (int) snapshot.getWidth();
        int height = (int) snapshot.getHeight();
        int[] pixels = new int[width * height];
        snapshot.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        try {
            Path target = Path.of(directory).resolve(name);
            Files.createDirectories(target.getParent());
            ImageIO.write(image, "png", target.toFile());
        } catch (Exception failure) {
            throw new IllegalStateException("无法保存模型配置窗口证据", failure);
        }
    }
}
