package com.javaclaw.desktop.settings;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.NativeUiEvidence;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;

/** 使用真实管理中心导航、作用域与网站页生成完整窗口证据；全部 SDK 数据和偏好保留在内存。 */
public final class SiteSettingsWindowEvidence {
    private SiteSettingsWindowEvidence() {}

    /**
     * 生成两种完整 Scene 尺寸的未选择、基本信息、账号、空列表和读取失败截图。
     *
     * <p>调用者必须设置 javaclaw.browser.evidence 输出目录，以及 javaclaw.site.evidence.schema 指向构建导出的网站 Schema。 此入口不连接 App
     * Server、不调用模型，不修改用户偏好；结束时释放所有窗口和 JavaFX Runtime。
     *
     * @param arguments 保留参数，当前不使用
     */
    public static void main(String[] arguments) {
        requireProperty("javaclaw.browser.evidence");
        requireProperty("javaclaw.site.evidence.schema");
        try {
            FxTestSupport.run(() -> {
                for (int width : List.of(880, 1040)) {
                    int height = width == 880 ? 620 : 720;
                    captureSelectedStates(width, height);
                    captureEmptyAndError(width, height);
                }
            });
        } finally {
            Platform.exit();
        }
    }

    private static void captureSelectedStates(int width, int height) {
        try (EvidenceSession session = open(new SiteSettingsTestGateway(), width, height)) {
            TableView<?> sites = table(session.root());
            require(sites.getSelectionModel().isEmpty(), "初始状态不应选择网站");
            session.capture("empty-selection", width);
            sites.getSelectionModel().select(0);
            require(section(session.root(), "site-basic").isExpanded(), "选中网站后应展开基本信息");
            session.capture("basic", width);
            section(session.root(), "site-accounts").setExpanded(true);
            session.capture("accounts", width);
            TitledPane basic = section(session.root(), "site-basic");
            require(!basic.isExpanded(), "账号展开时基本信息必须折叠");
            require(basic.getHeight() < 80, "基本信息折叠后仍占用过多高度：" + basic.getHeight());
            session.captureAccountDetails(width);
        }
    }

    private static void captureEmptyAndError(int width, int height) {
        SiteSettingsTestGateway empty = new SiteSettingsTestGateway();
        empty.sites.clear();
        try (EvidenceSession session = open(empty, width, height)) {
            require(table(session.root()).getItems().isEmpty(), "空目录仍然显示网站");
            session.capture("empty-list", width);
        }
        SiteSettingsTestGateway failed = new SiteSettingsTestGateway();
        failed.nextLoad = CompletableFuture.failedFuture(new IllegalStateException("连接暂时不可用"));
        try (EvidenceSession session = open(failed, width, height)) {
            require(
                    session.root().lookupAll(".button").stream()
                            .filter(Button.class::isInstance)
                            .map(Button.class::cast)
                            .anyMatch(button -> button.getText().equals("重试") && !button.isDisabled()),
                    "读取失败缺少重试动作");
            session.capture("load-error", width);
        }
    }

    private static EvidenceSession open(SiteSettingsTestGateway extensions, int width, int height) {
        DesktopAppearanceManager appearance = new DesktopAppearanceManager(new MemoryAppearanceStore());
        DesktopPresenter presenter = new DesktopPresenter(
                notifications -> {
                    throw new IOException("网站窗口证据不连接 App Server");
                },
                Platform::runLater,
                Clock.systemUTC());
        Stage owner = new Stage();
        owner.setScene(new Scene(new VBox(), 1, 1));
        owner.setOpacity(0);
        owner.show();
        ManagementCenterWindow center =
                new ManagementCenterWindow(appearance, gateways(presenter, extensions), new MemoryWindowStore());
        try {
            center.show(owner, "site");
            Scene production = managementStage().getScene();
            Parent root = production.getRoot();
            // 复用生产控件树，在固定 Scene 尺寸下包含实际导航与作用域占用，避免把全宽误给页面内容。
            production.setRoot(new VBox());
            Scene scene = new Scene(root, width, height);
            DesktopStylesheets.apply(scene);
            DesktopAppearanceManager.apply(scene, AppearancePreferences.defaults());
            root.applyCss();
            root.layout();
            return new EvidenceSession(center, presenter, owner, scene);
        } catch (RuntimeException | Error failure) {
            close(center, presenter, owner);
            throw failure;
        }
    }

    private static ManagementSettingsGateways gateways(DesktopPresenter presenter, SiteSettingsTestGateway extensions) {
        ManagementSettingsGateways base = SdkManagementSettingsGateways.create(presenter);
        return new ManagementSettingsGateways(
                new TestCoreSettingsGateway(),
                base.promptPreview(),
                base.promptOptimization(),
                base.mcp(),
                base.instructions(),
                base.bundles(),
                base.builtins(),
                base.jobs(),
                base.coding(),
                base.schedules(),
                extensions,
                () -> Optional.of(DesktopTestFixtures.workspace().id()));
    }

    private static Stage managementStage() {
        return Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(stage -> stage.isShowing() && "JavaClaw 设置与管理中心".equals(stage.getTitle()))
                .findFirst()
                .orElseThrow();
    }

    private static TableView<?> table(Parent root) {
        return (TableView<?>) root.lookup("#site-list");
    }

    private static TitledPane section(Parent root, String id) {
        return (TitledPane) root.lookup("#" + id);
    }

    private static void assertViewport(Parent root) {
        ScrollPane scroll = (ScrollPane) root.lookup(".platform-content-scroll");
        require(scroll != null, "未找到生产页面滚动容器");
        double available = scroll.getViewportBounds().getWidth();
        double content = scroll.getContent().getLayoutBounds().getWidth();
        require(available > 0 && content <= available + 1, "网站页内容超出实际视口：内容=" + content + "，视口=" + available);
        boolean horizontal = root.lookupAll(".scroll-bar").stream()
                .filter(ScrollBar.class::isInstance)
                .map(ScrollBar.class::cast)
                .anyMatch(bar -> bar.getOrientation() == Orientation.HORIZONTAL && visible(bar));
        require(!horizontal, "管理中心出现可见的水平滚动条");
    }

    private static boolean visible(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static void requireProperty(String property) {
        require(!System.getProperty(property, "").isBlank(), "运行证据入口前必须设置 " + property);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void close(ManagementCenterWindow center, DesktopPresenter presenter, Stage owner) {
        center.dispose();
        owner.hide();
        try {
            presenter.close();
        } catch (Exception failure) {
            throw new AssertionError("网站窗口证据 Presenter 关闭失败", failure);
        }
    }

    /**
     * 一次独立的生产窗口场景，拥有全部窗口和离线 Presenter 的释放责任。
     *
     * @param center 使用内存偏好的生产管理中心
     * @param presenter 不连接服务器的 SDK 装配入口
     * @param owner 管理中心所属的透明窗口
     * @param scene 包含完整生产根节点的固定尺寸 Scene
     */
    private record EvidenceSession(ManagementCenterWindow center, DesktopPresenter presenter, Stage owner, Scene scene)
            implements AutoCloseable {
        private Parent root() {
            return scene.getRoot();
        }

        private void capture(String state, int width) {
            NativeUiEvidence.capture(root(), "site-window-" + state + "-" + width + ".png");
            root().applyCss();
            root().layout();
            assertViewport(root());
        }

        private void captureAccountDetails(int width) {
            ScrollPane scroll = (ScrollPane) root().lookup(".platform-content-scroll");
            TitledPane accounts = section(root(), "site-accounts");
            double top = scroll.getContent()
                    .sceneToLocal(accounts.localToScene(0, 0))
                    .getY();
            double extent = scroll.getContent().getLayoutBounds().getHeight()
                    - scroll.getViewportBounds().getHeight();
            scroll.setVvalue(extent <= 0 ? 0 : Math.clamp((top - 8) / extent, 0, 1));
            capture("account-details", width);
        }

        @Override
        public void close() {
            SiteSettingsWindowEvidence.close(center, presenter, owner);
        }
    }

    private static final class MemoryAppearanceStore implements AppearancePreferenceStore {
        private AppearancePreferences preferences = AppearancePreferences.defaults();

        @Override
        public AppearancePreferences load() {
            return preferences;
        }

        @Override
        public void save(AppearancePreferences saved) {
            preferences = saved;
        }
    }

    private static final class MemoryWindowStore implements ManagementWindowPreferenceStore {
        private ManagementWindowPreferences preferences = new ManagementWindowPreferences("site", Optional.empty());

        @Override
        public ManagementWindowPreferences load() {
            return preferences;
        }

        @Override
        public void save(ManagementWindowPreferences saved) {
            preferences = saved;
        }
    }
}
