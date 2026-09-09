package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformStylesheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConfigurationPanelTest {
    @Test
    void 主工具栏只常驻模型思考更多和发送动作() {
        FxTestSupport.run(() -> {
            PanelGateway gateway = new PanelGateway();
            try (ChatConfigurationPanel panel = panel(gateway)) {
                assertTrue(panel.ready());
                assertEquals("Fake model ▾", ((Button) panel.lookup("#chatModel")).getText());
                assertTrue(reasoning(panel).getItems().contains(null));
                assertTrue(reasoning(panel).getItems().contains(ReasoningPreference.NONE));
                assertEquals(
                        1,
                        visibleButtons(panel).stream()
                                .filter(button -> button.getText().equals("更多 ⋯"))
                                .count());
                assertEquals(
                        1,
                        visibleButtons(panel).stream()
                                .filter(button -> button.getText().equals("发送 ↑"))
                                .count());
                assertFalse(descendants(panel).stream()
                        .filter(Label.class::isInstance)
                        .map(Label.class::cast)
                        .anyMatch(label -> label.isVisible() && label.getText().contains("继承来源")));
            }
        });
    }

    @Test
    void Agent固定配置时展示服务端实际模型和思考并锁定思考控件() {
        FxTestSupport.run(() -> {
            PanelGateway gateway = new PanelGateway();
            gateway.locked = true;
            try (ChatConfigurationPanel panel = panel(gateway)) {
                Button model = (Button) panel.lookup("#chatModel");
                assertEquals("Fake model · Agent 固定", model.getText());
                assertFalse(model.isDisabled());
                assertEquals(ReasoningPreference.HIGH, reasoning(panel).getValue());
                assertTrue(reasoning(panel).isDisabled());
                assertTrue(reasoning(panel).getTooltip().getText().contains("Agent 固定"));
                assertTrue(panel.ready());
            }
        });
    }

    @Test
    void 思考保存失败保留选择和原版本刷新后重试不提升草稿基线() {
        FxTestSupport.run(() -> {
            PanelGateway gateway = new PanelGateway();
            gateway.failSave = true;
            try (ChatConfigurationPanel panel = panel(gateway)) {
                reasoning(panel).setValue(ReasoningPreference.NONE);

                assertFalse(panel.ready());
                assertEquals(
                        Optional.of(ReasoningPreference.NONE), panel.execution().reasoning());
                assertTrue(visibleButtons(panel).stream()
                        .anyMatch(button -> button.getText().equals("重试")));
                gateway.revision = 9;
                panel.refresh();
                assertEquals(
                        Optional.of(ReasoningPreference.NONE), panel.execution().reasoning());
                visibleButtons(panel).stream()
                        .filter(button -> button.getText().equals("重试"))
                        .findFirst()
                        .orElseThrow()
                        .fire();

                assertEquals(
                        List.of(7L, 7L),
                        gateway.savedOptions.stream()
                                .map(CommandOptions::expectedRevision)
                                .toList());
                assertTrue(panel.ready());
                assertTrue(gateway.remembered);
            }
        });
    }

    @Test
    void 恢复项目设置仅移除模型思考并保留权限审批与能力限制() {
        FxTestSupport.run(() -> {
            PanelGateway gateway = new PanelGateway();
            gateway.saved = new ExecutionOverrides(
                    Optional.empty(),
                    Optional.of(gateway.model),
                    Optional.of(new PermissionProfileRef("permission-a", 1)),
                    Optional.of(ApprovalPolicy.EVERY_CALL),
                    Optional.empty(),
                    Optional.of(Set.of("core/read")),
                    Optional.of(ReasoningPreference.HIGH));
            try (ChatConfigurationPanel panel = panel(gateway)) {
                panel.discard();

                assertTrue(gateway.saved.provider().isEmpty());
                assertTrue(gateway.saved.reasoning().isEmpty());
                assertEquals(
                        Optional.of(new PermissionProfileRef("permission-a", 1)), gateway.saved.permissionProfile());
                assertEquals(Optional.of(ApprovalPolicy.EVERY_CALL), gateway.saved.approvalPolicy());
                assertEquals(Optional.of(Set.of("core/read")), gateway.saved.visibleCapabilities());
                assertFalse(gateway.remembered);
            }
        });
    }

    @Test
    void 缺工作区提供明确入口且不允许发送() {
        FxTestSupport.run(() -> {
            AtomicInteger choose = new AtomicInteger();
            try (ChatConfigurationPanel panel =
                    new ChatConfigurationPanel(new PanelGateway(), () -> {}, choose::incrementAndGet, () -> {})) {
                panel.bind(Optional.empty(), Optional.empty());
                assertFalse(panel.ready());
                visibleButtons(panel).stream()
                        .filter(button -> button.getText().equals("选择工作区"))
                        .findFirst()
                        .orElseThrow()
                        .fire();
                assertEquals(1, choose.get());
            }
        });
    }

    @Test
    void 常规与窄宽度布局中模型思考更多和发送均留在组件范围内() {
        FxTestSupport.run(() -> {
            for (int width : List.of(720, 360)) {
                try (ChatConfigurationPanel panel = panel(new PanelGateway())) {
                    Scene scene = new Scene(panel, width, 180);
                    PlatformStylesheets.applyTo(panel);
                    panel.applyCss();
                    panel.resize(width, 180);
                    panel.layout();
                    assertEquals(width, scene.getWidth());
                    for (Node control : List.of(
                            panel.lookup("#chatModel"),
                            reasoning(panel),
                            visibleButtons(panel).stream()
                                    .filter(button -> button.getText().equals("更多 ⋯"))
                                    .findFirst()
                                    .orElseThrow(),
                            panel.lookup("#testSend"))) {
                        var bounds = panel.sceneToLocal(control.localToScene(control.getBoundsInLocal()));
                        assertTrue(bounds.getMaxX() <= width + 1, control + " 超出宽度 " + width);
                        assertTrue(bounds.getMinX() >= -1, control + " 超出左边界");
                    }
                }
            }
        });
    }

    static ChatConfigurationPanel panel(PanelGateway gateway) {
        ChatConfigurationPanel panel = new ChatConfigurationPanel(gateway, () -> {}, () -> {}, () -> {});
        Button stop = new Button("停止");
        stop.setVisible(false);
        stop.setManaged(false);
        Button send = new Button("发送 ↑");
        send.setId("testSend");
        send.getStyleClass().addAll("send-button", "composer-send-button");
        panel.setActions(stop, send);
        panel.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
        return panel;
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ReasoningPreference> reasoning(Parent panel) {
        return (ComboBox<ReasoningPreference>) panel.lookup("#chatReasoning");
    }

    private static List<Button> visibleButtons(Parent panel) {
        return descendants(panel).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(Button::isVisible)
                .toList();
    }

    private static List<Node> descendants(Parent parent) {
        return parent.getChildrenUnmodifiable().stream()
                .flatMap(child -> child instanceof Parent nested
                        ? java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(child), descendants(nested).stream())
                        : java.util.stream.Stream.of(child))
                .toList();
    }

    static final class PanelGateway extends TestCoreSettingsGateway {
        final ProviderRef model = new ProviderRef("provider-main", 1, "fake-model");
        final List<CommandOptions> savedOptions = new ArrayList<>();
        ExecutionOverrides saved = ExecutionOverrides.empty();
        long revision = 7;
        boolean locked;
        boolean failSave;
        boolean remembered;

        @Override
        public CompletionStage<Optional<ExecutionConfiguration>> threadExecution(
                WorkspaceId workspace, ThreadId thread) {
            return CompletableFuture.completedFuture(Optional.of(configuration(workspace, thread)));
        }

        @Override
        public CompletionStage<ExecutionPreview> previewChatExecution(
                WorkspaceId workspace, Optional<ThreadId> thread, ExecutionOverrides execution) {
            return CompletableFuture.completedFuture(new ExecutionPreview(
                    Optional.of(new AgentRoleRef("default", 1)),
                    Optional.of(model),
                    locked ? Optional.of(ReasoningPreference.HIGH) : execution.reasoning(),
                    locked,
                    locked,
                    List.of(),
                    List.of()));
        }

        @Override
        public CompletionStage<ExecutionConfiguration> rememberChatSelection(
                WorkspaceId workspace,
                ThreadId thread,
                ExecutionOverrides execution,
                CommandOptions options,
                boolean remember) {
            savedOptions.add(options);
            remembered = remember;
            if (failSave) {
                failSave = false;
                return CompletableFuture.failedFuture(new IllegalStateException("保存暂不可用"));
            }
            saved = execution;
            revision = options.expectedRevision() + 1;
            return CompletableFuture.completedFuture(configuration(workspace, thread));
        }

        private ExecutionConfiguration configuration(WorkspaceId workspace, ThreadId thread) {
            return new ExecutionConfiguration(
                    Optional.of(workspace), Optional.of(thread), saved, revision, DesktopTestFixtures.NOW);
        }
    }
}
