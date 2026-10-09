package com.javaclaw.ui.javafx.desktop;

import com.javaclaw.desktop.api.*;
import com.javaclaw.platform.fx.FxDispatcher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

/** Explicit human text entry in JavaFX. The passive AWT preview never accepts keyboard focus. */
public final class DesktopManualInputPanel {
    private static final Map<String, DesktopManualInputPanel> OPEN = new ConcurrentHashMap<>();
    private static final FxDispatcher FX = new FxDispatcher();
    private final DesktopSessionOwner owner;
    private final DesktopSessionInfo info;
    private final DesktopSessionService service;
    private final Stage stage = new Stage();
    private final ComboBox<DesktopElement> elements = new ComboBox<>();
    private final ComboBox<DesktopAction.TextOperation> operation = new ComboBox<>();
    private final TextArea text = new TextArea();
    private final Label status = new Label("正在等待当前输入完成并暂停自动输入…");
    private final Button submit = new Button("提交文本");
    private final Button refresh = new Button("刷新画面与控件");
    private final ImageView image = new ImageView();
    private String lease;
    private DesktopObservation observation;
    private volatile String inFlightObservationId;
    private volatile boolean closed;
    private boolean busy = true;

    public static void open(DesktopSessionOwner owner, DesktopSessionInfo info,
            DesktopSessionService service) {
        if (!Platform.isFxApplicationThread()) {
            FX.dispatchLater(() -> open(owner, info, service));
            return;
        }
        DesktopManualInputPanel existing = OPEN.get(info.sessionId());
        if (existing != null) { existing.stage.show(); existing.stage.toFront(); return; }
        DesktopManualInputPanel panel = new DesktopManualInputPanel(owner, info, service);
        OPEN.put(info.sessionId(), panel);
        panel.start();
    }

    public static void closeSession(String sessionId) {
        if (!OPEN.containsKey(sessionId)) return;
        fx(() -> {
            DesktopManualInputPanel panel = OPEN.get(sessionId);
            if (panel != null) panel.stage.close();
        });
    }

    private DesktopManualInputPanel(DesktopSessionOwner owner, DesktopSessionInfo info,
            DesktopSessionService service) {
        this.owner = owner;
        this.info = info;
        this.service = service;
        elements.setId("desktop-manual-elements");
        operation.setId("desktop-manual-operation");
        text.setId("desktop-manual-text");
        submit.setId("desktop-manual-submit");
        refresh.setId("desktop-manual-refresh");
        image.setId("desktop-manual-image");
        stage.setTitle("人工文本输入 · " + info.target().application());
        Window.getWindows().stream().filter(Window::isShowing).findFirst().ifPresent(stage::initOwner);
        elements.setMaxWidth(Double.MAX_VALUE);
        elements.setConverter(new StringConverter<>() {
            @Override public String toString(DesktopElement element) {
                return element == null ? "" : element.role() + " · " + element.label()
                        + " (" + element.x() + ", " + element.y() + ")";
            }
            @Override public DesktopElement fromString(String value) { return null; }
        });
        operation.getItems().setAll(DesktopAction.TextOperation.values());
        if (info.inputPolicy() == DesktopInputPolicy.SYSTEM_EXPLICIT)
            operation.getItems().setAll(DesktopAction.TextOperation.INSERT_TEXT);
        operation.setValue(DesktopAction.TextOperation.INSERT_TEXT);
        operation.setConverter(new StringConverter<>() {
            @Override public String toString(DesktopAction.TextOperation value) {
                return value == DesktopAction.TextOperation.SET_TEXT ? "设置完整文本（覆盖原值）" : "在当前选区插入文本";
            }
            @Override public DesktopAction.TextOperation fromString(String value) { return null; }
        });
        operation.valueProperty().addListener((_, _, _) -> updateSubmit());
        elements.valueProperty().addListener((_, _, _) -> updateSubmit());
        text.textProperty().addListener((_, _, _) -> updateSubmit());
        text.setPromptText("通过公开控件接口提交；不使用系统键盘或剪贴板");
        text.setPrefRowCount(4);
        image.setPreserveRatio(true);
        image.setFitWidth(600);
        image.setFitHeight(320);
        status.setWrapText(true);
        refresh.setOnAction(_ -> refresh());
        submit.setOnAction(_ -> submit());
        Button close = new Button("关闭并恢复自动输入");
        close.setOnAction(_ -> stage.close());
        VBox root = new VBox(10, new Label("此面板打开期间，同一目标的自动输入暂停。"), image,
                new Label("目标控件"), elements, operation, text,
                new HBox(10, refresh, submit, close), status);
        root.setPadding(new Insets(16));
        stage.setScene(new Scene(root, 650, 650));
        stage.setOnHidden(_ -> close());
        updateSubmit();
    }

    private void start() {
        stage.show();
        service.acquireManualControl(owner, info.sessionId()).whenComplete((value, error) -> fx(() -> {
            if (closed) {
                if (value != null) release(value);
                return;
            }
            if (error != null) { fail("无法暂停自动输入", error); return; }
            lease = value;
            if (info.inputPolicy() == DesktopInputPolicy.SYSTEM_EXPLICIT) {
                busy = false;
                text.setDisable(true);
                status.setText("自动输入已暂停。此会话使用系统鼠标键盘，请到目标应用直接输入；独立文本输入需要选择后台公开控件操作并重新打开会话。");
                updateSubmit();
                return;
            }
            refresh();
        }));
    }

    private void refresh() {
        if (closed || lease == null) return;
        busy = true;
        observation = null;
        image.setImage(null);
        elements.getItems().clear();
        updateSubmit();
        service.captureObservation(owner, info.sessionId()).whenComplete((value, error) -> fx(() -> {
            if (closed) return;
            busy = false;
            if (error != null) { fail("读取画面失败", error); return; }
            if (value.isEmpty()) {
                status.setText("暂无可用的新画面；等待目标恢复后点击刷新。自动输入仍暂停。");
                updateSubmit();
                return;
            }
            observation = value.get();
            DesktopFrame frame = observation.frame();
            WritableImage snapshot = new WritableImage(frame.width(), frame.height());
            snapshot.getPixelWriter().setPixels(0, 0, frame.width(), frame.height(),
                    PixelFormat.getByteBgraPreInstance(), frame.bgraPremultiplied(), 0, frame.stride());
            image.setImage(snapshot);
            elements.getItems().setAll(observation.elements().stream().filter(element ->
                    (element.actions() & (DesktopElement.INSERT_TEXT | DesktopElement.SET_TEXT)) != 0).toList());
            elements.getSelectionModel().selectFirst();
            status.setText(elements.getItems().isEmpty()
                    ? "当前画面没有公开文本控件；不能后台输入。自动输入仍暂停。"
                    : "请选择画面中的控件并确认文本操作。自动输入仍暂停。");
            updateSubmit();
        }));
    }

    private void submit() {
        if (busy || observation == null || lease == null) return;
        DesktopObservation observed = observation;
        String submitLease = lease;
        DesktopElement element = elements.getValue();
        if (element == null) return;
        DesktopAction action = new DesktopAction(DesktopAction.Kind.TYPE,
                element.centerX(), element.centerY(), 0, 0, 0, text.getText(),
                observed.frame().windowGeneration(), observed.observationId(), element.id(),
                observed.frame().contentRevision(), operation.getValue());
        busy = true;
        observation = null; // Every displayed observation authorizes at most one attempt.
        updateSubmit();
        service.commitObservation(owner, info.sessionId(), observed.observationId(), List.of())
                .thenCompose(committed -> {
                    if (closed) throw new IllegalStateException("人工输入面板已关闭");
                    if (!committed) throw new IllegalStateException("画面已过期，请刷新后确认");
                    inFlightObservationId = action.observationId();
                    var dispatch = service.performManual(owner, info.sessionId(), submitLease, action);
                    if (closed) markUncertain(action.observationId());
                    return dispatch;
                }).whenComplete((result, error) -> {
                    if (result == null && inFlightObservationId != null) markUncertain(action.observationId());
                    fx(() -> {
                        if (closed) {
                            if (inFlightObservationId != null) markUncertain(action.observationId());
                            return;
                        }
                        busy = false;
                        if (error != null) { fail("文本未完成", error); return; }
                        status.setText(result.status() + " · " + result.detail()
                                + "；请刷新并确认结果。自动输入仍暂停。");
                        // A native return is not yet a delivered human result until this UI update.
                        service.acknowledgeActionResult(owner, info.sessionId(), action.observationId());
                        inFlightObservationId = null;
                        updateSubmit();
                    });
                });
    }

    private void updateSubmit() {
        DesktopElement element = elements.getValue();
        int needed = operation.getValue() == DesktopAction.TextOperation.SET_TEXT
                ? DesktopElement.SET_TEXT : DesktopElement.INSERT_TEXT;
        submit.setDisable(busy || observation == null || element == null
                || (element.actions() & needed) == 0 || text.getLength() > 8192
                || (needed == DesktopElement.INSERT_TEXT && text.getText().isEmpty()));
        refresh.setDisable(busy || lease == null || info.inputPolicy() == DesktopInputPolicy.SYSTEM_EXPLICIT);
    }

    private void fail(String message, Throwable error) {
        busy = false;
        status.setText(message + "：" + error.getClass().getSimpleName() + "；请刷新或关闭面板。");
        updateSubmit();
    }

    private void close() {
        if (closed) return;
        closed = true;
        OPEN.remove(info.sessionId(), this);
        if (inFlightObservationId != null) markUncertain(inFlightObservationId);
        if (lease != null) release(lease);
        lease = null;
        observation = null;
        elements.getItems().clear();
        image.setImage(null);
        text.clear();
    }

    private void release(String token) {
        try { service.releaseManualControl(owner, info.sessionId(), token); }
        catch (IllegalArgumentException | IllegalStateException | SecurityException closedSession) {
            // Closing the owning session already revokes its target lease.
            observation = null;
        }
    }

    private void markUncertain(String observationId) {
        try { service.markDeliveryUncertain(owner, info.sessionId(), observationId); }
        catch (IllegalStateException noStartedAction) {
            // No registered dispatch, or its result was already acknowledged.
            inFlightObservationId = null;
        }
    }

    private static void fx(Runnable task) {
        if (Platform.isFxApplicationThread()) task.run(); else FX.dispatchLater(task);
    }
}
