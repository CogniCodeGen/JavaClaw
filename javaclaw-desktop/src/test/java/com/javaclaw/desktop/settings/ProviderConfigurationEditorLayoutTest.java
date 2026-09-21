package com.javaclaw.desktop.settings;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在含导航的真实设置窗口中检查单页编辑器；所有服务及目录来自内存替身，不调用模型。 */
class ProviderConfigurationEditorLayoutTest {
    private static final String LONG_MODEL = "vendor/" + "long-model-identifier-".repeat(5);

    @Test
    void 最小设置窗口保留模型目录底栏和密钥恢复焦点() {
        exercise(880, 620);
    }

    @Test
    void 常规设置窗口保持顶部服务选择器并完整显示保存反馈() {
        exercise(1040, 720);
    }

    @Test
    void 宽设置窗口显示服务侧栏及模型左右详情布局() {
        exercise(1440, 900);
    }

    private static void exercise(int width, int height) {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture(width, height)) {
                Parent root = fixture.root();
                capture(root, "single-page-connect-" + width + "x" + height + ".png");
                assertFooter(root);
                assertServiceArrangement(root, width);
                text(root, "providerWizardAddress").setText("http://localhost:11434/v1");
                text(root, "providerWizardSecret").setText("local-layout-fixture-key");
                modelSection(root).setExpanded(true);
                settle(root);
                button(root, "providerWizardDiscoverModels").fire();
                settle(root);
                ListView<?> directory = (ListView<?>) root.lookup("#providerWizardModelsList");
                assertEquals(1_000, directory.getItems().size());
                selectByKeyboard(directory, 0);
                selectByKeyboard(directory, 1);
                settle(root);
                assertTrue(hasLabel(root, "已选 2 个模型"));
                focusPurpose(root);
                capture(root, "single-page-models-" + width + "x" + height + ".png");
                assertModelArrangement(root);
                assertDirectoryAndFooter(root);
                fixture.gateway.preparationResponse = CompletableFuture.failedFuture(new IllegalArgumentException(
                        "com.javaclaw.client.facade.PreparedProviderConfiguration$DigestInput: "
                                + "private-layout-secret=".repeat(80)));
                button(root, "providerConfigurationSave").fire();
                settle(root);
                capture(root, "single-page-prepare-failure-" + width + "x" + height + ".png");
                assertFailureLayout(fixture);
                restoreDirectory(fixture, directory, width, height);
            }
        });
    }

    private static void assertFailureLayout(Fixture fixture) {
        Parent root = fixture.root();
        Label status = (Label) root.lookup(".platform-action-status");
        assertTrue(status.getText().contains("配置未能提交"));
        assertTrue(status.getText().contains("填写内容已保留"));
        assertFalse(status.getText().contains("DigestInput"));
        assertFalse(status.getTooltip().getText().contains("private-layout-secret"));
        assertTrue(status.getText().length() < 160, "长异常只能投影为简短中文反馈");
        assertFooter(root);
        TitledPane models = modelSection(root);
        assertInside(models.lookup(".title"));
        if (((ProviderConfigurationEditor) root.lookup("#providerConfigurationEditor")).getHeight() < 620) {
            assertFalse(models.isExpanded(), "矮窗应为密钥恢复优先展示连接区域，模型草稿保留在折叠区");
        } else if (models.isExpanded()) {
            assertDirectoryAndFooter(root);
        }
        assertEquals(1, fixture.gateway.preparations);
        assertTrue(fixture.gateway.saved.isEmpty());
        assertTrue(fixture.gateway.queried.isEmpty());
        TextField secret = text(root, "providerWizardSecret");
        assertSame(secret, root.getScene().getFocusOwner(), "失败后键盘焦点必须回到可补填的密钥字段");
        ScrollPane connection = (ScrollPane) root.lookup("#providerConnectionScroll");
        Bounds viewport = sceneBounds(connection.lookup(".viewport"));
        Bounds secretBounds = sceneBounds(secret);
        assertTrue(secretBounds.getMinY() >= viewport.getMinY() - 1, "密钥不能在连接视口上方不可见");
        assertTrue(secretBounds.getMaxY() <= viewport.getMaxY() + 1, "密钥不能在连接视口下方不可见");
    }

    private static void restoreDirectory(Fixture fixture, ListView<?> original, int width, int height) {
        Parent root = fixture.root();
        String failure = ((Label) root.lookup(".platform-action-status")).getText();
        modelSection(root).setExpanded(true);
        settle(root);
        focusPurpose(root);
        capture(root, "single-page-recovered-models-" + width + "x" + height + ".png");
        assertSame(original, root.lookup("#providerWizardModelsList"), "折叠不得重建模型目录");
        assertEquals(1_000, original.getItems().size());
        ProviderSetupModelForm models =
                (ProviderSetupModelForm) modelSection(root).getContent();
        assertEquals(2, models.selectedModels().size(), "补钥区域与模型区域切换必须保留选择和属性");
        assertEquals(fixture.gateway.configuration.models(), models.selectedModels());
        assertTrue(hasLabel(root, "已选 2 个模型"));
        assertEquals(failure, ((Label) root.lookup(".platform-action-status")).getText(), "展示模型不能覆盖保存失败反馈");
        assertModelArrangement(root);
        assertDirectoryAndFooter(root);
    }

    private static TitledPane modelSection(Parent root) {
        return (TitledPane) root.lookup("#providerModelsSection");
    }

    private static void assertServiceArrangement(Parent root, int width) {
        assertNotNull(root.lookup(".management-navigation-list"), "必须验证包含全局导航的真实设置窗口");
        if (width < 1440) {
            assertNotNull(root.lookup("#providerServicesCompact"));
            assertInside(root.lookup("#providerServicesCompact"));
            assertTrue(root.lookup("#providerServicesList") == null, "窄窗口移除服务侧栏，不额外占用模型区宽度");
        } else {
            assertNotNull(root.lookup("#providerServicesList"));
            assertInside(root.lookup("#providerServicesList"));
            assertTrue(root.lookup("#providerServicesCompact") == null);
        }
        BorderPane page = (BorderPane) root.lookup(".settings-content-area");
        assertFalse(page.getCenter() instanceof ScrollPane, "模型页必须独立分配列表及表单视口");
    }

    private static void assertModelArrangement(Parent root) {
        BorderPane directory = (BorderPane) root.lookup("#providerWizardDirectory");
        Node details = root.lookup("#providerWizardModelDetails");
        ProviderSetupModelForm models =
                (ProviderSetupModelForm) modelSection(root).getContent();
        ScrollPane scroll = null;
        if (models.getWidth() < 760) {
            scroll = (ScrollPane) root.lookup("#providerWizardModelDetailsScroll");
            assertNotNull(scroll, "窄模型区域使用独立详情滚动视口");
            assertSame(scroll, directory.getBottom());
            assertSame(details, scroll.getContent());
            assertInside(scroll);
            assertWithin(scroll, sceneBounds(models.getParent()), "TitledPane 内容视口");
        } else {
            assertSame(details, directory.getRight(), "足够宽时详情固定在模型列表右侧");
            assertInside(details);
        }
        assertModelFieldsNotClipped(root, models, details, scroll);
    }

    private static void assertModelFieldsNotClipped(
            Parent root, ProviderSetupModelForm models, Node details, ScrollPane scroll) {
        Node viewport = models.getParent();
        assertNotNull(viewport, "模型表单必须附着在真实 TitledPane 内容视口中");
        Bounds content = sceneBounds(viewport);
        Bounds form = sceneBounds(models);
        Bounds footer = sceneBounds(root.lookup(".platform-action-bar"));
        for (String selector :
                List.of("#providerWizardCurrentModelId", "#providerWizardModelPurpose", ".provider-image-support")) {
            Node control = details.lookup(selector);
            assertNotNull(control, selector);
            if (scroll != null) {
                control.requestFocus();
                settle(root);
                assertSame(control, root.getScene().getFocusOwner(), selector + " 必须能获得键盘焦点");
                assertWithin(control, sceneBounds(scroll.lookup(".viewport")), "详情滚动视口");
            }
            assertInside(control);
            assertWithin(control, content, "TitledPane 内容视口");
            assertWithin(control, form, "模型表单");
            assertTrue(sceneBounds(control).getMaxY() <= footer.getMinY() + 1, () -> selector + " 不能落入固定底栏区域");
        }
        Node rows = root.lookup("#providerWizardModelsList");
        assertWithin(rows, content, "TitledPane 内容视口");
        assertWithin(rows, form, "模型表单");
    }

    private static void focusPurpose(Parent root) {
        root.lookup("#providerWizardModelPurpose").requestFocus();
        settle(root);
    }

    private static void assertWithin(Node control, Bounds viewport, String description) {
        Bounds bounds = sceneBounds(control);
        String identifier = control.getId() == null ? control.getAccessibleText() : control.getId();
        assertTrue(
                bounds.getMinX() >= viewport.getMinX() - 1 && bounds.getMaxX() <= viewport.getMaxX() + 1,
                () -> identifier + " 超出" + description + "横向边界，可能被裁剪：" + bounds + "；视口：" + viewport);
        assertTrue(
                bounds.getMinY() >= viewport.getMinY() - 1 && bounds.getMaxY() <= viewport.getMaxY() + 1,
                () -> identifier + " 超出" + description + "纵向边界，可能被裁剪：" + bounds + "；视口：" + viewport);
    }

    private static void assertDirectoryAndFooter(Parent root) {
        assertFooter(root);
        ListView<?> directory = (ListView<?>) root.lookup("#providerWizardModelsList");
        assertInside(directory);
        assertTrue(directory.getHeight() >= 110, "即使显示保存错误，目录也应保留可用的可视区域");
        Bounds rows = sceneBounds(directory);
        Bounds footer = sceneBounds(root.lookup(".platform-action-bar"));
        assertTrue(rows.getMaxY() <= footer.getMinY() + 1, "错误反馈和固定底栏不能覆盖模型列表");
        assertTrue(directory.lookupAll(".list-cell").size() < 100, "1000 条目录必须保留虚拟化，不能构造全部控件");
    }

    private static void assertFooter(Parent root) {
        for (String id : List.of("providerConfigurationSave", "providerConfigurationDiscard")) {
            Button action = button(root, id);
            assertInside(action);
            javafx.scene.text.Text rendered = (javafx.scene.text.Text) action.lookup(".text");
            assertEquals(action.getText(), rendered.getText(), "底部按钮文字不能被省略");
        }
    }

    private static void assertInside(Node node) {
        assertNotNull(node);
        assertTrue(node.isVisible() && node.isManaged(), node.getId());
        Bounds bounds = sceneBounds(node);
        assertTrue(bounds.getMinX() >= -1 && bounds.getMinY() >= -1, () -> node.getId() + " 越过窗口起点：" + bounds);
        assertTrue(bounds.getMaxX() <= node.getScene().getWidth() + 1, () -> node.getId() + " 越过右边界：" + bounds);
        assertTrue(bounds.getMaxY() <= node.getScene().getHeight() + 1, () -> node.getId() + " 越过下边界：" + bounds);
    }

    private static Bounds sceneBounds(Node node) {
        return node.localToScene(node.getLayoutBounds());
    }

    private static void selectByKeyboard(ListView<?> rows, int index) {
        rows.getSelectionModel().select(index);
        rows.requestFocus();
        assertSame(rows, rows.getScene().getFocusOwner());
        Event.fireEvent(rows, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE, false, false, false, false));
        Event.fireEvent(rows, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.SPACE, false, false, false, false));
    }

    private static void settle(Parent root) {
        root.applyCss();
        root.layout();
        Object key = new Object();
        PauseTransition pulse = new PauseTransition(Duration.millis(80));
        pulse.setOnFinished(ignored -> Platform.exitNestedEventLoop(key, null));
        pulse.play();
        Platform.enterNestedEventLoop(key);
        root.applyCss();
        root.layout();
    }

    private static void capture(Parent root, String name) {
        String directory = System.getProperty("javaclaw.provider.evidence", "");
        if (directory.isEmpty()) {
            return;
        }
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
            Files.writeString(
                    target.resolveSibling(name.replaceFirst("\\.png$", ".metadata.txt")),
                    captureMetadata(root, width, height));
        } catch (IOException failure) {
            throw new AssertionError("无法写入真实模型配置界面截图", failure);
        }
    }

    private static String captureMetadata(Parent root, int snapshotWidth, int snapshotHeight) {
        Scene scene = root.getScene();
        Window window = scene.getWindow();
        ProviderSetupModelForm models =
                (ProviderSetupModelForm) modelSection(root).getContent();
        return String.join(
                        "\n",
                        "capture=javafx.scene.Scene.snapshot",
                        "os.name=" + System.getProperty("os.name"),
                        "os.arch=" + System.getProperty("os.arch"),
                        "javafx.version=" + System.getProperty("javafx.version", "unknown"),
                        "window.logical.width=" + window.getWidth(),
                        "window.logical.height=" + window.getHeight(),
                        "scene.logical.width=" + scene.getWidth(),
                        "scene.logical.height=" + scene.getHeight(),
                        "window.outputScaleX=" + window.getOutputScaleX(),
                        "window.outputScaleY=" + window.getOutputScaleY(),
                        "window.renderScaleX=" + window.getRenderScaleX(),
                        "window.renderScaleY=" + window.getRenderScaleY(),
                        "snapshot.pixel.width=" + snapshotWidth,
                        "snapshot.pixel.height=" + snapshotHeight,
                        "modelForm.logical.width=" + models.getWidth(),
                        "modelForm.logical.height=" + models.getHeight(),
                        "coverage=Only this native window and its observed scale; other platforms and DPI are not implied.")
                + "\n";
    }

    private static boolean hasLabel(Parent root, String expected) {
        return root.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(label -> label.getText().equals(expected));
    }

    private static TextField text(Parent root, String id) {
        return (TextField) root.lookup("#" + id);
    }

    private static Button button(Parent root, String id) {
        return (Button) root.lookup("#" + id);
    }

    /** 真实窗口与内存 Gateway；关闭释放 JavaFX 窗口、会话及测试 SDK，不写个人偏好。 */
    private static final class Fixture implements AutoCloseable {
        private final ProviderConfigurationTestGateway gateway = new ProviderConfigurationTestGateway();
        private final RoleSettingsTestGateway rolePanels = new RoleSettingsTestGateway();
        private final DesktopPresenter desktop = new DesktopPresenter(
                ignored -> {
                    throw new IOException("布局证据测试不连接服务");
                },
                Platform::runLater,
                Clock.systemUTC());
        private final Stage owner = new Stage();
        private final ManagementCenterWindow center;
        private final Stage window;

        private Fixture(int width, int height) {
            ManagementSettingsGateways base = SdkManagementSettingsGateways.create(desktop);
            ManagementSettingsGateways gateways = new ManagementSettingsGateways(
                    gateway,
                    rolePanels.prompts,
                    rolePanels.optimization,
                    base.mcp(),
                    base.instructions(),
                    base.bundles(),
                    base.builtins(),
                    base.jobs(),
                    base.coding(),
                    base.schedules(),
                    base.extensions(),
                    Optional::empty);
            gateway.candidates = IntStream.range(0, 1_000)
                    .mapToObj(index -> new ProviderModelDiscoveryCandidate(
                            index == 0 ? LONG_MODEL : "vendor/model-%04d".formatted(index),
                            "目录模型 %04d".formatted(index),
                            Set.of(ProviderModelPurpose.CHAT),
                            OptionalInt.empty()))
                    .toList();
            center = new ManagementCenterWindow(
                    new DesktopAppearanceManager(new AppearanceStore()), gateways, new WindowStore());
            owner.setScene(new Scene(new VBox(), 600, 400));
            owner.show();
            center.showProviderCreation(owner);
            window = Window.getWindows().stream()
                    .filter(Stage.class::isInstance)
                    .map(Stage.class::cast)
                    .filter(candidate -> candidate.getOwner() == owner)
                    .findFirst()
                    .orElseThrow();
            window.setWidth(width);
            window.setHeight(height);
            window.requestFocus();
            settle(root());
        }

        private Parent root() {
            return window.getScene().getRoot();
        }

        @Override
        public void close() {
            center.dispose();
            owner.hide();
            rolePanels.configurationEvents.close();
            try {
                desktop.close();
            } catch (Exception failure) {
                throw new AssertionError("关闭布局测试 SDK 失败", failure);
            }
        }
    }

    private static final class AppearanceStore implements AppearancePreferenceStore {
        @Override
        public AppearancePreferences load() {
            return AppearancePreferences.defaults();
        }

        @Override
        public void save(AppearancePreferences preferences) {}
    }

    private static final class WindowStore implements ManagementWindowPreferenceStore {
        @Override
        public ManagementWindowPreferences load() {
            return ManagementWindowPreferences.defaults();
        }

        @Override
        public void save(ManagementWindowPreferences preferences) {}
    }
}
