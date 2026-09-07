package com.javaclaw.desktop;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.shell.DesktopShellController;
import com.javaclaw.desktop.shell.JavaClawDesktop;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.view.ViewCommandInvocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopShellControllerTest {
    @Test
    void 断线时真实壳显示可操作错误卡且不泄漏传输层原文() throws Exception {
        AtomicReference<DesktopPresenter> presenter = new AtomicReference<>();
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<Map<String, Object>> controls = new AtomicReference<>();
        FxTestSupport.run(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(JavaClawDesktop.class.getResource("/fxml/main.fxml"));
                Parent root = loader.load();
                Stage window = new Stage();
                Scene scene = new Scene(root, 1_280, 820);
                DesktopStylesheets.apply(scene);
                window.setScene(scene);
                window.show();
                DesktopPresenter disconnected = new DesktopPresenter(
                        notifications -> {
                            throw new IOException("Connection refused: /private/user/socket");
                        },
                        Platform::runLater,
                        Clock.systemUTC());
                var appearance = new com.javaclaw.desktop.appearance.DesktopAppearanceManager(
                        new com.javaclaw.desktop.appearance.AppearancePreferenceStore() {
                            @Override
                            public com.javaclaw.desktop.appearance.AppearancePreferences load() {
                                return com.javaclaw.desktop.appearance.AppearancePreferences.defaults();
                            }

                            @Override
                            public void save(com.javaclaw.desktop.appearance.AppearancePreferences preferences) {}
                        });
                var center = new com.javaclaw.desktop.settings.ManagementCenterWindow(
                        appearance, com.javaclaw.desktop.settings.SdkManagementSettingsGateways.create(disconnected));
                loader.<DesktopShellController>getController().attach(disconnected, center);
                presenter.set(disconnected);
                stage.set(window);
                controls.set(new HashMap<>(loader.getNamespace()));
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });

        FxTestSupport.await(
                () -> FxTestSupport.call(() -> ((VBox) controls.get().get("connectionErrorCard")).isVisible()));
        FxTestSupport.run(() -> {
            VBox card = (VBox) controls.get().get("connectionErrorCard");
            Label detail = (Label) controls.get().get("connectionErrorDetail");
            Button start = (Button) controls.get().get("connectionStartButton");
            assertTrue(card.isManaged());
            assertFalse(detail.getText().contains("Connection refused"));
            assertFalse(detail.getText().contains("/private/user/socket"));
            assertTrue(start.isDisabled());
            assertFalse(start.getAccessibleText().isBlank());
        });
        presenter.get().close();
        FxTestSupport.run(stage.get()::hide);
    }

    @Test
    void fxmlShellRendersStateAndRoutesUserIntentThroughPresenter() throws Exception {
        ShellHarness shell = new ShellHarness();
        try {
            shell.start();
            FxTestSupport.await(() -> shell.latest.get().connection().status() == ConnectionState.Status.CONNECTED
                    && shell.latest.get().transcript().nextSequence() == 1);
            shell.assertConnectedControls();
            shell.exerciseInputRequest();
            shell.exerciseTogglesSelectionsAndCells();
            shell.exerciseSendApprovalAndCancellation();
            shell.exerciseManagementCenter();
        } finally {
            shell.close();
        }
    }

    private static final class ShellHarness {
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final DesktopPresenter presenter;
        private final AtomicReference<DesktopState> latest = new AtomicReference<>();
        private DesktopShellController controller;
        private Map<String, Object> controls;
        private Stage stage;

        private ShellHarness() throws IOException {
            JavaClawClient client = server.client(ignored -> {});
            presenter = new DesktopPresenter(
                    notifications -> client,
                    ShellHarness::dispatch,
                    Clock.fixed(DesktopTestFixtures.NOW, ZoneOffset.UTC));
            presenter.subscribe(latest::set);
        }

        private void start() {
            FxTestSupport.run(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(JavaClawDesktop.class.getResource("/fxml/main.fxml"));
                    Parent root = loader.load();
                    controller = loader.getController();
                    controls = new HashMap<>(loader.getNamespace());
                    stage = new Stage();
                    stage.setScene(new Scene(root, 1_280, 820));
                    stage.show();
                    controller.attach(presenter);
                    assertThrows(IllegalStateException.class, () -> controller.attach(presenter));
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            });
        }

        private void assertConnectedControls() {
            FxTestSupport.run(() -> {
                assertEquals(
                        "javaclaw-app-server 6.0.0-SNAPSHOT",
                        label("connectionLabel").getText());
                assertEquals("架构升级", label("threadTitle").getText());
                assertEquals(1, list("transcriptList").getItems().size());
                assertFalse(button("sendButton").isDisabled());
                assertNotNull(combo("executionRole"));
                assertNotNull(combo("executionModel"));
                assertNotNull(combo("executionPermission"));
                assertNotNull(combo("executionReasoning"));
                assertTrue(button("interruptButton").isDisabled());
                assertFalse(label("errorLabel").isVisible());
                assertTrue(button("approveButton").getStyleClass().contains("jc-btn-primary"));
                assertTrue(button("denyButton").getStyleClass().contains("jc-btn-danger"));
                assertFalse(
                        control("inputRequestHost", VBox.class).getChildren().isEmpty());
            });
        }

        private void exerciseInputRequest() {
            FxTestSupport.await(
                    () -> !latest.get().interaction().inputs().pendingRequests().isEmpty());
            FxTestSupport.run(() -> {
                VBox host = control("inputRequestHost", VBox.class);
                TextField mode = host.lookupAll(".settings-field").stream()
                        .filter(TextField.class::isInstance)
                        .map(TextField.class::cast)
                        .filter(field -> "请输入mode".equals(field.getPromptText()))
                        .findFirst()
                        .orElseThrow();
                TextField retries = host.lookupAll(".settings-field").stream()
                        .filter(TextField.class::isInstance)
                        .map(TextField.class::cast)
                        .filter(field -> "请输入整数".equals(field.getPromptText()))
                        .findFirst()
                        .orElseThrow();
                CheckBox confirmed = (CheckBox) host.lookup(".settings-checkbox");
                mode.setText("safe");
                retries.setText("2");
                confirmed.setSelected(true);
                host.lookupAll(".button").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(candidate -> "提交输入".equals(candidate.getText()))
                        .findFirst()
                        .orElseThrow()
                        .fire();
            });
            FxTestSupport.await(() -> server.inputResolutions.get() == 1
                    && latest.get().interaction().inputs().pendingRequests().isEmpty());
            assertEquals("{\"confirmed\":true,\"mode\":\"safe\",\"retries\":2}", server.lastInputResponse.json());
        }

        private void exerciseTogglesSelectionsAndCells() {
            FxTestSupport.run(() -> {
                VBox sidebar = control("sidebar", VBox.class);
                VBox progress = control("progressPanel", VBox.class);
                controller.toggleSidebar();
                controller.toggleProgress();
                assertFalse(sidebar.isVisible());
                assertFalse(sidebar.isManaged());
                assertFalse(progress.isVisible());
                assertFalse(progress.isManaged());
                controller.toggleSidebar();
                controller.toggleProgress();

                ComboBox<Workspace> workspaces = combo("workspaceBox");
                workspaces.setValue(null);
                workspaces.setValue(server.workspace());
                assertCellRendering(workspaces, list("threadList"), combo("executionRole"));
            });
            FxTestSupport.await(() -> !latest.get().threads().threads().isEmpty()
                    && latest.get().threads().selectedThread().isPresent());
            FxTestSupport.run(() -> {
                ListView<ConversationThread> threads = list("threadList");
                threads.getSelectionModel().clearSelection();
                threads.getSelectionModel().select(server.thread());
                ComboBox<AgentRole> roles = combo("executionRole");
                roles.setValue(null);
                roles.setValue(server.profile());
            });
            FxTestSupport.await(() -> latest.get().threads().selectedThread().isPresent());
            FxTestSupport.run(controller::useDefaultRole);
            FxTestSupport.await(() -> latest.get().interaction().selectedRole().isEmpty());
            FxTestSupport.run(() -> assertNull(combo("executionRole").getValue()));
        }

        private void exerciseSendApprovalAndCancellation() {
            FxTestSupport.run(() -> {
                controller.approve();
                TextArea composer = control("composer", TextArea.class);
                composer.setText("  ");
                controller.send();
                assertTrue(label("errorLabel").isVisible());
                assertFalse(label("errorLabel").getText().isBlank());
            });
            server.completion = TurnStatus.RUNNING;
            FxTestSupport.await(
                    () -> FxTestSupport.call(() -> !combo("executionRole").isDisabled()
                            && !combo("executionRole").getItems().isEmpty()));
            FxTestSupport.run(() -> {
                combo("executionRole").setValue(server.profile());
                combo("executionReasoning").setValue(ReasoningPreference.HIGH);
                control("composer", TextArea.class).setText("执行并等待审批");
                controller.send();
                assertEquals("", control("composer", TextArea.class).getText());
            });
            FxTestSupport.await(() -> latest.get().threads().activeTurn().isPresent()
                    && !latest.get().interaction().pendingApprovals().isEmpty());
            assertEquals(
                    new AgentRoleRef(server.profile().id(), server.profile().revision()),
                    server.lastTurnStart.execution().role().orElseThrow());
            assertEquals(
                    ReasoningPreference.HIGH,
                    server.lastTurnStart.execution().reasoning().orElseThrow());
            assertTrue(server.lastTurnStart.execution().provider().isEmpty());
            assertTrue(server.lastTurnStart.execution().permissionProfile().isEmpty());
            FxTestSupport.run(() -> {
                assertFalse(button("interruptButton").isDisabled());
                ListView<ApprovalRecord> approvals = list("approvalList");
                approvals.getSelectionModel().selectFirst();
                assertFalse(button("approveButton").isDisabled());
                controller.approve();
            });
            FxTestSupport.await(() -> server.approvalResolutions.get() == 1);
            FxTestSupport.run(() -> {
                ListView<ApprovalRecord> approvals = list("approvalList");
                approvals.getSelectionModel().selectFirst();
                controller.deny();
                controller.interrupt();
            });
            FxTestSupport.await(() -> server.approvalResolutions.get() == 2
                    && server.turnCancels.get() == 1
                    && !latest.get().interaction().busy());

            assertThrows(IllegalArgumentException.class, () -> new ViewCommandInvocation("put", Map.of(), -1, false));
        }

        private void exerciseManagementCenter() {
            int baseline = FxTestSupport.call(() -> Window.getWindows().size());
            FxTestSupport.run(controller::openSettings);
            FxTestSupport.await(
                    () -> FxTestSupport.call(() -> Window.getWindows().size()) > baseline);
            FxTestSupport.run(() -> assertTrue(Window.getWindows().stream()
                    .filter(Stage.class::isInstance)
                    .map(Stage.class::cast)
                    .filter(window -> "JavaClaw 设置与管理中心".equals(window.getTitle()))
                    .anyMatch(Window::isShowing)));
            long count = FxTestSupport.call(() -> Window.getWindows().stream()
                    .filter(Stage.class::isInstance)
                    .map(Stage.class::cast)
                    .filter(window -> "JavaClaw 设置与管理中心".equals(window.getTitle()))
                    .count());
            FxTestSupport.run(controller::openSettings);
            assertEquals(
                    count,
                    FxTestSupport.call(() -> Window.getWindows().stream()
                            .filter(Stage.class::isInstance)
                            .map(Stage.class::cast)
                            .filter(window -> "JavaClaw 设置与管理中心".equals(window.getTitle()))
                            .count()));
        }

        private void assertCellRendering(
                ComboBox<Workspace> workspaces, ListView<ConversationThread> threads, ComboBox<AgentRole> roles) {
            ListCell<Workspace> workspaceCell = workspaces.getCellFactory().call(null);
            update(workspaceCell, server.workspace(), false);
            assertEquals("工作区", workspaceCell.getText());
            update(workspaceCell, null, false);
            assertNull(workspaceCell.getText());

            ListCell<ConversationThread> threadCell = threads.getCellFactory().call(threads);
            update(threadCell, server.thread(), false);
            assertEquals("架构升级", threadCell.getText());
            update(threadCell, server.thread(), true);
            assertNull(threadCell.getText());

            ListCell<AgentRole> roleCell = roles.getCellFactory().call(null);
            update(roleCell, server.profile(), false);
            assertEquals(server.profile().spec().name(), roleCell.getText());
            update(roleCell, null, true);
            assertNull(roleCell.getText());

            ListView<ApprovalRecord> approvals = list("approvalList");
            ListCell<ApprovalRecord> approvalCell = approvals.getCellFactory().call(approvals);
            update(approvalCell, DesktopTestFixtures.approval(com.javaclaw.api.ApprovalState.PENDING), false);
            VBox approvalGraphic = (VBox) approvalCell.getGraphic();
            assertTrue(
                    ((Label) approvalGraphic.getChildren().getFirst()).getText().contains("read_file"));
            assertTrue(approvalGraphic.getStyleClass().contains("platform-detail-cell"));
            update(approvalCell, null, true);
            assertNull(approvalCell.getGraphic());

            ListView<ItemEnvelope> transcript = list("transcriptList");
            ListCell<ItemEnvelope> transcriptCell = transcript.getCellFactory().call(transcript);
            update(transcriptCell, DesktopTestFixtures.item(1), false);
            assertNotNull(transcriptCell.getGraphic());
            update(transcriptCell, null, true);
            assertNull(transcriptCell.getGraphic());
        }

        private void close() throws Exception {
            presenter.close();
            FxTestSupport.run(() -> Window.getWindows().stream().toList().forEach(Window::hide));
        }

        @SuppressWarnings("unchecked")
        private <T> ComboBox<T> combo(String id) {
            return (ComboBox<T>) control(id, ComboBox.class);
        }

        @SuppressWarnings("unchecked")
        private <T> ListView<T> list(String id) {
            return (ListView<T>) controls.get(id);
        }

        private Label label(String id) {
            return control(id, Label.class);
        }

        private Button button(String id) {
            return control(id, Button.class);
        }

        private <T> T control(String id, Class<T> type) {
            return type.cast(
                    controls.containsKey(id)
                            ? controls.get(id)
                            : stage.getScene().lookup("#" + id));
        }

        private static void update(ListCell<?> cell, Object item, boolean empty) {
            Method method = java.util.Arrays.stream(cell.getClass().getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals("updateItem"))
                    .filter(candidate -> !candidate.isBridge())
                    .findFirst()
                    .orElseThrow();
            try {
                method.setAccessible(true);
                method.invoke(cell, item, empty);
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            } catch (InvocationTargetException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        private static void dispatch(Runnable action) {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        }
    }
}
