package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GeneralSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SaveResult;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopSystemPermissionService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import com.javaclaw.ui.javafx.theme.ThemeOption;
import com.javaclaw.ui.javafx.theme.ThemeSelectionService;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.util.Objects;
import java.util.function.Consumer;

/** 通用桌面行为设置 Controller；主题选择立即生效，持久化设置异步保存。 */
public final class GeneralSettingsController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ComboBox<ThemeOption> themeCombo;
    @FXML private ToggleSwitch minimizeToTrayCheck;
    @FXML private ToggleSwitch computerAppAccessCheck;
    @FXML private Label computerAppAccessStatus;
    @FXML private Button computerAppAccessPermissionButton;

    private final BehaviorSettingsApplicationService useCases;
    private final ThemeSelectionService themes;
    private final DesktopSystemPermissionService desktopPermissions;
    private final GeneralSettingsViewModel viewModel = new GeneralSettingsViewModel();
    private final UiAsyncAction<SaveResult> mutation;
    private final UiAsyncAction<LoadedGeneral> refresh;
    private final UiAsyncAction<PermissionAttempt> permissionRequest;
    private final ChangeListener<String> currentThemeListener =
            (ignored, previous, current) -> selectCurrentTheme(current);
    private final ChangeListener<Boolean> computerAccessListener =
            (ignored, previous, current) -> computerAccessChanged(current);
    private final ChangeListener<Scene> sceneListener =
            (ignored, previous, current) -> watchScene(current);
    private final ChangeListener<Window> windowListener =
            (ignored, previous, current) -> watchWindow(current);
    private final ChangeListener<Boolean> focusListener =
            (ignored, previous, focused) -> windowFocusChanged(focused);
    private final PauseTransition permissionReturnDelay =
            new PauseTransition(Duration.millis(400));
    private Scene observedScene;
    private Window observedWindow;
    private boolean pendingComputerAccess;
    private boolean leftForSystemSettings;
    private boolean returnCheckPending;
    private int awaitedCapability;
    private Consumer<SaveResult> onApplied = ignored -> { };

    public GeneralSettingsController(
            BehaviorSettingsApplicationService useCases,
            ThemeSelectionService themes,
            DesktopSystemPermissionService desktopPermissions,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.themes = Objects.requireNonNull(themes, "themes");
        this.desktopPermissions = Objects.requireNonNull(desktopPermissions, "desktopPermissions");
        mutation = new UiAsyncAction<>(tasks, fx);
        refresh = new UiAsyncAction<>(tasks, fx);
        permissionRequest = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        themeCombo.setItems(viewModel.themes());
        themeCombo.setConverter(new StringConverter<>() {
            @Override public String toString(ThemeOption theme) {
                return theme == null ? "" : theme.name() + " — " + theme.subtitle();
            }
            @Override public ThemeOption fromString(String value) { return null; }
        });
        themeCombo.getProperties().put("jc-dirty-exempt", Boolean.TRUE);
        themeCombo.valueProperty().bindBidirectional(viewModel.selectedThemeProperty());
        minimizeToTrayCheck.selectedProperty().bindBidirectional(
                viewModel.minimizeToTrayOnCloseProperty());
        computerAppAccessCheck.selectedProperty().bindBidirectional(
                viewModel.computerAppAccessEnabledProperty());
        computerAppAccessStatus.textProperty().bind(viewModel.computerAppAccessStatusProperty());
        computerAppAccessCheck.disableProperty().bind(
                refresh.busyProperty().or(permissionRequest.busyProperty())
                        .or(mutation.busyProperty()));
        computerAppAccessPermissionButton.disableProperty().bind(
                refresh.busyProperty().or(permissionRequest.busyProperty())
                        .or(mutation.busyProperty()));
        computerAppAccessCheck.selectedProperty().addListener(computerAccessListener);
        root.sceneProperty().addListener(sceneListener);
        watchScene(root.getScene());
        permissionReturnDelay.setOnFinished(ignored -> refreshAfterSystemSettings());
        viewModel.busyProperty().bind(mutation.busyProperty().or(refresh.busyProperty())
                .or(permissionRequest.busyProperty()));
        themes.currentThemeProperty().addListener(currentThemeListener);
    }

    public void configure(Consumer<SaveResult> callback) {
        onApplied = Objects.requireNonNull(callback, "callback");
    }

    public GeneralSettingsViewModel viewModel() {
        return viewModel;
    }

    public void reload() {
        refresh.execute(TaskSpec.io("settings-general-load"),
                context -> new LoadedGeneral(useCases.snapshot().general(),
                        desktopPermissions.status()),
                value -> SettingsFieldSupport.loading(root, () -> {
                    viewModel.load(value.settings(), themes.availableThemes(),
                            themes.currentTheme());
                    showPermissionStatus(value.permission());
                }),
                failure -> viewModel.errorProperty().set(
                        SettingsFieldSupport.failureMessage(failure)));
    }

    public void save(Consumer<SaveResult> success, Consumer<Throwable> failure) {
        GeneralSettings command = new GeneralSettings(minimizeToTrayCheck.isSelected(),
                computerAppAccessCheck.isSelected());
        boolean savedComputerAccess = useCases.snapshot().general().computerAppAccessEnabled();
        mutation.execute(TaskSpec.io("settings-general-save"),
                context -> {
                    if (command.computerAppAccessEnabled()) {
                        DesktopAvailability status = desktopPermissions.status();
                        if (!status.available()) {
                            throw new PendingDesktopPermissionException(status.detail());
                        }
                    }
                    return useCases.saveGeneral(command);
                }, result -> {
                    pendingComputerAccess = false;
                    reload();
                    onApplied.accept(result);
                    success.accept(result);
                }, thrown -> {
                    if (!(thrown instanceof PendingDesktopPermissionException)) {
                        pendingComputerAccess = false;
                        SettingsFieldSupport.loading(root,
                                () -> computerAppAccessCheck.setSelected(savedComputerAccess));
                    }
                    if (thrown instanceof PendingDesktopPermissionException) {
                        pendingComputerAccess = true;
                    }
                    String statusMessage = DesktopPermissionStatusText.saveFailureMessage(
                            command.computerAppAccessEnabled(), savedComputerAccess,
                            thrown instanceof PendingDesktopPermissionException, thrown);
                    if (statusMessage != null) {
                        viewModel.computerAppAccessStatusProperty().set(statusMessage);
                    }
                    failed(thrown, failure);
                });
    }

    @FXML
    private void themeChanged() {
        ThemeOption selected = themeCombo.getValue();
        if (!SettingsFieldSupport.isLoading(root) && selected != null
                && !selected.id().equals(themes.currentThemeId())) {
            themes.select(selected.id());
        }
    }

    @FXML private void minimizeToTrayRowClicked(MouseEvent event) {
        SettingsToggleRow.toggle(event, minimizeToTrayCheck);
    }

    @FXML private void computerAppAccessRowClicked(MouseEvent event) {
        SettingsToggleRow.toggle(event, computerAppAccessCheck);
    }

    @FXML private void refreshComputerPermission() {
        pendingComputerAccess = true;
        requestComputerPermissions();
    }

    private void computerAccessChanged(boolean enabled) {
        if (SettingsFieldSupport.isLoading(root)) return;
        if (!enabled) {
            pendingComputerAccess = false;
            leftForSystemSettings = false;
            returnCheckPending = false;
            awaitedCapability = 0;
            permissionReturnDelay.stop();
            permissionRequest.cancel();
            viewModel.computerAppAccessStatusProperty().set(
                    "已关闭。保存后，智能体将无法使用电脑应用会话工具。");
            return;
        }
        pendingComputerAccess = true;
        requestComputerPermissions();
    }

    private void requestComputerPermissions() {
        viewModel.computerAppAccessStatusProperty().set("正在检查并申请系统权限…");
        permissionRequest.execute(TaskSpec.io("settings-desktop-permission-request"),
                context -> requestMissingPermissions(), this::permissionChecked,
                thrown -> viewModel.computerAppAccessStatusProperty().set(
                        "无法检查或申请系统权限："
                                + SettingsFieldSupport.failureMessage(thrown)));
    }

    private PermissionAttempt requestMissingPermissions() {
        DesktopAvailability status = desktopPermissions.status();
        if (status.available()) return new PermissionAttempt(status, 0);
        int requested = firstMissingCapability(status);
        return new PermissionAttempt(desktopPermissions.requestPermissions(), requested);
    }

    private void permissionChecked(PermissionAttempt attempt) {
        DesktopAvailability status = attempt.status();
        awaitedCapability = attempt.requestedCapability();
        if (status.available() || status.providerId().isBlank()) {
            pendingComputerAccess = false;
        }
        showPermissionStatus(status);
        if (returnCheckPending) {
            returnCheckPending = false;
            if (pendingComputerAccess) permissionReturnDelay.playFromStart();
        } else if (pendingComputerAccess && awaitedCapability != 0
                && (status.capabilities() & awaitedCapability) != 0
                && observedWindow != null && observedWindow.isFocused()) {
            // A synchronous OS grant can advance without leaving the app.
            permissionReturnDelay.playFromStart();
        }
    }

    private void watchScene(Scene scene) {
        if (observedScene != null) observedScene.windowProperty().removeListener(windowListener);
        observedScene = scene;
        if (scene != null) scene.windowProperty().addListener(windowListener);
        watchWindow(scene == null ? null : scene.getWindow());
    }

    private void watchWindow(Window window) {
        if (observedWindow != null) observedWindow.focusedProperty().removeListener(focusListener);
        observedWindow = window;
        if (window != null) window.focusedProperty().addListener(focusListener);
    }

    private void windowFocusChanged(boolean focused) {
        if (!pendingComputerAccess) return;
        if (!focused) {
            leftForSystemSettings = true;
        } else if (leftForSystemSettings) {
            leftForSystemSettings = false;
            permissionReturnDelay.playFromStart();
        }
    }

    void refreshAfterSystemSettings() {
        if (!pendingComputerAccess || SettingsFieldSupport.isLoading(root)) return;
        if (permissionRequest.busyProperty().get()) {
            returnCheckPending = true;
            return;
        }
        int prompted = awaitedCapability;
        viewModel.computerAppAccessStatusProperty().set("正在重新检查系统权限…");
        permissionRequest.execute(TaskSpec.io("settings-desktop-permission-return"),
                context -> {
                    DesktopAvailability status = desktopPermissions.status();
                    if (status.available()) return new PermissionAttempt(status, 0);
                    // Advance only after the permission that was last requested
                    // becomes available. An unchanged return must not reopen it.
                    if (promptedPermissionWasGranted(prompted, status)) {
                        int next = firstMissingCapability(status);
                        return new PermissionAttempt(desktopPermissions.requestPermissions(), next);
                    }
                    return new PermissionAttempt(status, prompted);
                }, this::permissionChecked,
                thrown -> viewModel.computerAppAccessStatusProperty().set(
                        "无法重新检查系统权限："
                                + SettingsFieldSupport.failureMessage(thrown)));
    }

    private void showPermissionStatus(DesktopAvailability status) {
        viewModel.computerAppAccessStatusProperty().set(
                DesktopPermissionStatusText.statusMessage(status, pendingComputerAccess,
                        awaitedCapability, computerAppAccessCheck.isSelected(),
                        () -> useCases.snapshot().general().computerAppAccessEnabled()));
    }

    static String permissionChecklist(DesktopAvailability status) {
        return DesktopPermissionStatusText.permissionChecklist(status);
    }

    static int firstMissingCapability(DesktopAvailability status) {
        return DesktopPermissionStatusText.firstMissingCapability(status);
    }

    static boolean promptedPermissionWasGranted(int prompted, DesktopAvailability status) {
        return DesktopPermissionStatusText.promptedPermissionWasGranted(prompted, status);
    }

    private void selectCurrentTheme(String themeId) {
        ThemeOption selected = viewModel.themes().stream()
                .filter(theme -> theme.id().equals(themeId))
                .findFirst()
                .orElse(null);
        if (selected != null) {
            SettingsFieldSupport.loading(root,
                    () -> viewModel.selectedThemeProperty().set(selected));
        }
    }

    private void failed(Throwable thrown, Consumer<Throwable> failure) {
        viewModel.errorProperty().set(SettingsFieldSupport.failureMessage(thrown));
        failure.accept(thrown);
    }

    void deactivate() {
        refresh.cancel();
        permissionRequest.cancel();
        permissionReturnDelay.stop();
        pendingComputerAccess = false;
        leftForSystemSettings = false;
        returnCheckPending = false;
        awaitedCapability = 0;
    }

    @Override
    public void close() {
        mutation.close();
        refresh.close();
        permissionRequest.close();
        permissionReturnDelay.stop();
        root.sceneProperty().removeListener(sceneListener);
        watchScene(null);
        themes.currentThemeProperty().removeListener(currentThemeListener);
        viewModel.busyProperty().unbind();
        computerAppAccessCheck.selectedProperty().removeListener(computerAccessListener);
        computerAppAccessCheck.disableProperty().unbind();
        computerAppAccessPermissionButton.disableProperty().unbind();
        computerAppAccessStatus.textProperty().unbind();
        themeCombo.valueProperty().unbindBidirectional(viewModel.selectedThemeProperty());
        minimizeToTrayCheck.selectedProperty().unbindBidirectional(
                viewModel.minimizeToTrayOnCloseProperty());
        computerAppAccessCheck.selectedProperty().unbindBidirectional(
                viewModel.computerAppAccessEnabledProperty());
    }

    private record LoadedGeneral(GeneralSettings settings,
                                 DesktopAvailability permission) { }

    private record PermissionAttempt(DesktopAvailability status,
                                     int requestedCapability) { }

    private static final class PendingDesktopPermissionException
            extends IllegalStateException {
        PendingDesktopPermissionException(String detail) {
            super("电脑应用系统权限未就绪：" + detail);
        }
    }
}
