package com.javaclaw.desktop.shell;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javafx.animation.PauseTransition;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.javaclaw.client.extension.CodingExecutionPoller;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.state.DesktopState;

/** 沿用进度侧栏控件的只读执行输出；不提供人工终端输入或独立执行入口。 */
public final class CodingExecutionPanel extends VBox {
    private static final int DISPLAY_CHARACTERS = 64 * 1024;
    private final TextArea output = new TextArea();
    private final PauseTransition clock = new PauseTransition(Duration.seconds(1));
    private final DesktopCodingOutputController controller;
    private Optional<DesktopCodingOutputController.Binding> desired = Optional.empty();

    /**
     * 创建使用当前 Desktop SDK 连接的输出面板。
     *
     * @param desktop 共享后台请求执行器
     */
    public CodingExecutionPanel(DesktopPresenter desktop) {
        this(poller -> desktop.submitSettingsRequest(
                client -> poller.poll(client.builtins().coding())));
    }

    CodingExecutionPanel(Function<CodingExecutionPoller, CompletionStage<CodingExecutionPoller.Poll>> gateway) {
        this.controller = new DesktopCodingOutputController(gateway, this::render);
        setSpacing(8);
        getStyleClass().add("desktop-execution-output");
        Label title = new Label("执行输出");
        title.getStyleClass().add("sec-title");
        output.setId("codingExecutionOutput");
        output.setEditable(false);
        output.setWrapText(true);
        output.setPrefRowCount(8);
        getChildren().setAll(title, output);
        setVisible(false);
        setManaged(false);
        clock.setOnFinished(event -> tick());
        sceneProperty().addListener((observable, previous, next) -> {
            if (next != null) {
                next.windowProperty().addListener((source, oldWindow, window) -> {
                    if (window != null) {
                        window.showingProperty().addListener((value, before, showing) -> refreshActivity());
                    }
                    refreshActivity();
                });
            }
            refreshActivity();
        });
    }

    /**
     * 同步会话和连接身份；普通转录刷新不会额外触发读取。
     *
     * @param state 当前 Desktop 快照
     */
    public void bind(DesktopState state) {
        desired = state.connection()
                .connectedAt()
                .flatMap(connected -> state.threads()
                        .selectedThread()
                        .map(thread -> new DesktopCodingOutputController.Binding(
                                new CodingExecutionPoller.Scope(
                                        thread.workspaceId(), Optional.of(thread.id()), Optional.empty()),
                                connected)));
        refreshActivity();
    }

    private void refreshActivity() {
        boolean showing = getScene() != null
                && getScene().getWindow() != null
                && getScene().getWindow().isShowing();
        controller.bind(showing ? desired : Optional.empty());
        if (showing && desired.isPresent()) {
            if (clock.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                clock.playFromStart();
            }
        } else {
            clock.stop();
        }
    }

    private void tick() {
        controller.poll();
        refreshActivity();
    }

    void render(DesktopCodingOutputController.State state) {
        StringBuilder text = new StringBuilder();
        for (var snapshot : state.snapshots()) {
            String entry = snapshot.fact().title() + "\n" + snapshot.fact().body() + "\n\n";
            if (text.length() + entry.length() > DISPLAY_CHARACTERS) {
                text.append("[显示预算已满；其他执行可通过 SDK 分页读取]\n");
                break;
            }
            text.append(entry);
        }
        state.failure().ifPresent(text::append);
        String next = text.toString();
        if (!output.getText().equals(next)) {
            output.setText(next);
            output.positionCaret(next.length());
        }
        setVisible(!next.isEmpty());
        setManaged(!next.isEmpty());
    }
}
