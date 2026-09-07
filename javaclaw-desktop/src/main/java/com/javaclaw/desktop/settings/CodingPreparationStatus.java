package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.animation.PauseTransition;
import javafx.scene.control.Label;
import javafx.util.Duration;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.extension.CodingTranscriptFormatter;
import com.javaclaw.desktop.component.FormSection;

/** 设置页的只读准备状态；一个作用域至多一个请求，隐藏后不再轮询。 */
final class CodingPreparationStatus {
    private final CodingSettingsGateway gateway;
    private final FormSection section = new FormSection("准备状态", "最近依赖准备的输出位于会话进度侧栏；取消仍作用于该 Turn。");
    private final Label status = new Label("尚未读取准备状态");
    private final PauseTransition clock = new PauseTransition(Duration.seconds(2));
    private Optional<WorkspaceId> workspace = Optional.empty();
    private long epoch;
    private boolean active;
    private boolean pending;

    CodingPreparationStatus(CodingSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        status.setId("codingPreparationStatus");
        status.setWrapText(true);
        section.addFullWidth(status);
        clock.setOnFinished(event -> poll());
    }

    FormSection section() {
        return section;
    }

    void bind(Optional<WorkspaceId> next, boolean showing) {
        if (!workspace.equals(next) || active != showing) {
            epoch++;
            workspace = next;
            active = showing;
            clock.stop();
            status.setText(next.isEmpty() ? "请先选择工作区" : "尚未读取准备状态");
            poll();
        }
    }

    void poll() {
        if (!active || workspace.isEmpty() || pending) {
            return;
        }
        long requestEpoch = epoch;
        pending = true;
        try {
            gateway.executions(workspace.orElseThrow()).whenComplete((result, failure) -> {
                pending = false;
                if (requestEpoch != epoch) {
                    if (active && workspace.isPresent()) {
                        clock.playFromStart();
                    }
                    return;
                }
                if (failure != null) {
                    status.setText("准备状态暂不可读取；请在会话中查看当前任务。");
                } else {
                    String text = result.executions().stream()
                            .filter(value -> value.operation().equals("dependencies_prepare"))
                            .limit(10)
                            .map(value ->
                                    value.state() + " · " + value.operationId() + " · " + value.outputBytes() + " 字节"
                                            + value.exitCode()
                                                    .map(code -> " · 退出码 " + code)
                                                    .orElse(""))
                            .collect(java.util.stream.Collectors.joining("\n"));
                    status.setText(text.isEmpty() ? "暂无依赖准备记录" : new CodingTranscriptFormatter.Fact("", text).body());
                }
                clock.playFromStart();
            });
        } catch (RuntimeException failure) {
            pending = false;
            status.setText("准备状态暂不可读取；请在会话中查看当前任务。");
            clock.playFromStart();
        }
    }
}
