package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformStylesheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupLayoutTest {
    private static final String MODEL_ID = "vendor/" + "long-model-identifier-".repeat(20);

    @Test
    void 窄窗口与常规窗口的固定底栏三按钮完整显示() {
        for (int width : List.of(560, 680)) {
            FxTestSupport.run(() -> {
                var gateway = new ProviderConfigurationTestGateway();
                gateway.candidates = List.of(candidate(MODEL_ID, MODEL_ID));
                Stage window = ProviderSetupWizardFxTest.open(gateway);
                try {
                    Parent root = window.getScene().getRoot();
                    ProviderSetupWizardFxTest.connect(root);
                    window.setWidth(width);
                    window.setHeight(580);
                    root.applyCss();
                    root.layout();
                    ProviderSetupWizardFxTest.modelChoice(root, MODEL_ID).fire();
                    assertFooterButtons(root);
                    assertInsideWindow(root.lookup("#providerWizardShowManual"));
                    assertInsideWindow(root.lookup("#providerWizardDiscoverModels"));
                    assertEquals(
                            "保存模型",
                            ProviderSetupWizardFxTest.button(root, "providerWizardContinue")
                                    .getText());
                } finally {
                    window.hide();
                }
            });
        }
    }

    @Test
    void 长候选名称换行且同名ID只显示一次并在搜索后保留选择() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = new ProviderSetupModelForm(new PlatformComponentFactory(), () -> {});
            new Scene(form, 460, 500);
            PlatformStylesheets.applyTo(form);
            form.candidates(List.of(candidate(MODEL_ID, MODEL_ID), candidate("different-model", "易读显示名称")));
            form.applyCss();
            form.resize(460, 500);
            form.layout();
            CheckBox choice = ProviderSetupWizardFxTest.modelChoice(form, MODEL_ID);
            assertEquals(MODEL_ID, choice.getText());
            assertEquals(MODEL_ID, choice.getTooltip().getText());
            assertTrue(choice.isWrapText());
            assertInsideWindow(choice);
            assertTrue(choice.getHeight() > choice.getFont().getSize() * 2);
            choice.fire();
            assertEquals(MODEL_ID, form.selectedModels().getFirst().modelId());
            assertEquals(
                    "易读显示名称 · different-model",
                    ProviderSetupWizardFxTest.modelChoice(form, "different-model")
                            .getText());
            ((TextField) form.lookup("#providerWizardSearch")).setText("no-match");
            form.candidates(List.of(candidate(MODEL_ID, "刷新后的名称")));
            ((TextField) form.lookup("#providerWizardSearch")).clear();
            assertTrue(ProviderSetupWizardFxTest.modelChoice(form, MODEL_ID).isSelected());
            assertEquals(MODEL_ID, form.selectedModels().getFirst().modelId());
        });
    }

    @Test
    void 真实窄窗口和常规窗口固定显示步骤标题目录错误与禁用理由() {
        for (int ownerWidth : List.of(880, 1040)) {
            WindowFixture fixture = FxTestSupport.call(() -> errorWindow(ownerWidth));
            try {
                FxTestSupport.await(() ->
                        FxTestSupport.call(() -> fixture.window().getScene().getHeight() <= fixture.modalHeight()));
                FxTestSupport.run(() -> assertFixedNotices(fixture));
            } finally {
                FxTestSupport.run(() -> {
                    fixture.window().hide();
                    fixture.owner().hide();
                });
            }
        }
    }

    private static WindowFixture errorWindow(int ownerWidth) {
        Stage owner = new Stage();
        owner.setScene(new Scene(new BorderPane(), ownerWidth, ownerWidth == 880 ? 620 : 720));
        owner.show();
        var gateway = new ProviderConfigurationTestGateway();
        gateway.previewResponses.add(
                CompletableFuture.failedFuture(new IllegalStateException("此服务未提供模型目录，请检查配置后重试，也可手动输入完整模型 ID。")));
        ProviderSetupWizard.configure(owner, gateway, Optional.empty(), 0, ignored -> {});
        Stage window = ProviderSetupWizardFxTest.window();
        Parent root = window.getScene().getRoot();
        ProviderSetupWizardFxTest.connect(root);
        ProviderSetupWizardFxTest.text(root, "providerWizardManualModel").setText("not-yet-added");
        int height = ownerWidth == 880 ? 540 : 620;
        window.setWidth(660);
        window.setHeight(height);
        return new WindowFixture(owner, window, height);
    }

    @Test
    void 单页模型区域增高时虚拟化目录使用剩余高度() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = new ProviderSetupModelForm(new PlatformComponentFactory(), () -> {});
            form.seed(List.of(
                    new ProviderModelSpec("model-one", "模型一", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())));
            BorderPane root = new BorderPane(form);
            Stage window = new Stage();
            window.setScene(new Scene(root, 660, 540));
            PlatformStylesheets.applyTo(root);
            window.show();
            try {
                root.applyCss();
                root.layout();
                ListView<?> directory = (ListView<?>) root.lookup("#providerWizardModelsList");
                double before = directory.getHeight();
                root.resize(660, 660);
                root.layout();
                assertTrue(directory.getHeight() > before + 80, "新增高度应分配给目录，不能留在表单底部");
                assertInsideWindow(root.lookup("#providerWizardShowManual"));
                assertInsideWindow(root.lookup("#providerWizardDiscoverModels"));
            } finally {
                window.hide();
            }
        });
    }

    @Test
    void 兼容向导窄窗口的手动添加和启用操作可滚动访问且底栏固定() {
        WindowFixture fixture = FxTestSupport.call(ProviderSetupLayoutTest::growingWindow);
        try {
            FxTestSupport.await(
                    () -> FxTestSupport.call(() -> fixture.window().getScene().getHeight() <= 540));
            FxTestSupport.run(() -> assertModelControlsReachable(fixture));
        } finally {
            FxTestSupport.run(() -> {
                fixture.window().hide();
                fixture.owner().hide();
            });
        }
    }

    private static WindowFixture growingWindow() {
        Stage owner = new Stage();
        owner.setScene(new Scene(new BorderPane(), 880, 620));
        owner.show();
        var gateway = new ProviderConfigurationTestGateway();
        gateway.candidates = List.of(candidate("model-one", "模型一"), candidate("model-two", "模型二"));
        ProviderSetupWizard.configure(owner, gateway, Optional.empty(), 0, ignored -> {});
        Stage window = ProviderSetupWizardFxTest.window();
        Parent root = window.getScene().getRoot();
        ProviderSetupWizardFxTest.connect(root);
        ProviderSetupWizardFxTest.manual(root, "confirmed-model");
        ((TitledPane) root.lookup("#providerWizardManualSection")).setExpanded(false);
        window.setWidth(660);
        window.setHeight(540);
        return new WindowFixture(owner, window, 540);
    }

    private static void assertModelControlsReachable(WindowFixture fixture) {
        Parent root = fixture.window().getScene().getRoot();
        root.applyCss();
        root.layout();
        ScrollPane body = (ScrollPane) root.lookup("#providerWizardBodyScroll");
        body.setVvalue(0);
        root.layout();
        Button manual = (Button) root.lookup("#providerWizardShowManual");
        assertVerticallyInside(manual);
        manual.fire();
        // 折叠区域不占布局；按真实入口展开后先重算内容高度，再滚动到末尾。
        root.applyCss();
        root.layout();
        body.setVvalue(1);
        root.layout();
        Node viewport = body.lookup(".viewport");
        Bounds visible = viewport.localToScene(viewport.getLayoutBounds());
        for (String id : List.of("providerWizardManualSection", "providerWizardEnable")) {
            Node control = root.lookup("#" + id);
            assertVerticallyInside(control);
            Bounds bounds = control.localToScene(control.getLayoutBounds());
            assertTrue(bounds.getMinY() >= visible.getMinY() - 1, id);
            assertTrue(bounds.getMaxY() <= visible.getMaxY() + 1, id + " 必须可滚动到视口内操作");
        }
        assertVerticallyInside(root.lookup("#providerWizardStatus"));
        assertVerticallyInside(root.lookup(".header-panel"));
        assertFooterButtons(root);
        assertTrue(((ListView<?>) root.lookup("#providerWizardModelsList")).getHeight() >= 110);
    }

    private static void assertFixedNotices(WindowFixture fixture) {
        Parent root = fixture.window().getScene().getRoot();
        root.applyCss();
        root.layout();
        Node header = root.lookup(".header-panel");
        Label status = (Label) root.lookup("#providerWizardStatus");
        Label reason = (Label) root.lookup("#providerWizardDisabledReason");
        ScrollPane scroll = (ScrollPane) root.lookup("#providerWizardBodyScroll");
        assertTrue(status.getText().contains("目录"));
        assertTrue(reason.getText().contains("添加"));
        assertEquals(scroll.getParent(), status.getParent(), "状态必须位于表单滚动区之外");
        assertEquals(scroll.getParent(), reason.getParent(), "禁用理由必须位于表单滚动区之外");
        assertVerticallyInside(header);
        assertVerticallyInside(status);
        assertVerticallyInside(reason);
        assertFooterButtons(root);
        assertTrue(status.getHeight() + 1 >= status.prefHeight(status.getWidth()), "目录错误完整换行");
        assertTrue(reason.getHeight() + 1 >= reason.prefHeight(reason.getWidth()), "禁用理由完整换行");
        scroll.setVvalue(1);
        root.layout();
        assertVerticallyInside(header);
        assertVerticallyInside(status);
        assertVerticallyInside(reason);
        ProviderSetupWizardFxTest.text(root, "providerWizardManualModel").clear();
        ProviderSetupWizardFxTest.manual(root, "confirmed-model");
        assertTrue(reason.getText().isEmpty());
        assertTrue(!reason.isManaged(), "空提示不占据固定底部空间");
    }

    private static void assertVerticallyInside(Node node) {
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        assertTrue(node.isVisible() && node.isManaged());
        assertTrue(bounds.getMinY() >= -1, () -> node.getId() + " 越过窗口上边界: " + bounds);
        assertTrue(bounds.getMaxY() <= node.getScene().getHeight() + 1, () -> node.getId() + " 越过窗口下边界: " + bounds);
    }

    private record WindowFixture(Stage owner, Stage window, int modalHeight) {}

    private static void assertInsideWindow(Node node) {
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        assertTrue(bounds.getMinX() >= -1, () -> node.getId() + " 越过窗口左边界: " + bounds);
        assertTrue(bounds.getMaxX() <= node.getScene().getWidth() + 1, () -> node.getId() + " 越过窗口右边界: " + bounds);
    }

    private static void assertFooterButtons(Parent root) {
        List<Button> buttons = root.lookupAll(".button-bar .button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .toList();
        assertEquals(3, buttons.size());
        for (Button button : buttons) {
            assertTrue(button.isVisible() && button.isManaged(), button.getText());
            assertInsideWindow(button);
            Text rendered = (Text) button.lookup(".text");
            assertEquals(button.getText(), rendered.getText(), () -> "底部按钮文字被省略：" + button.getText());
            Bounds bounds = button.localToScene(button.getLayoutBounds());
            assertTrue(bounds.getMaxY() <= root.getScene().getHeight() + 1, button.getText());
        }
    }

    private static ProviderModelDiscoveryCandidate candidate(String id, String name) {
        return new ProviderModelDiscoveryCandidate(id, name, Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty());
    }
}
