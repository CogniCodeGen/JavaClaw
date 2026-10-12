package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.application.schedule.ScheduleApplicationService.RuntimeState;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import com.javaclaw.application.schedule.ScheduleExecutionStatus;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.schedule.ExecutionPolicy;
import com.javaclaw.task.TaskNotificationChannel;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.util.StringConverter;

import java.time.format.DateTimeFormatter;

/** 详情视图的选项格式与只读运行状态渲染；不改变任务或表单提交状态。 */
final class ScheduleDetailViewSupport {
    private static final DateTimeFormatter NEXT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private ScheduleDetailViewSupport() { }

    static void initializeOptions(ComboBox<String> intervalUnits, ComboBox<ExecutionPolicy> policies,
                                  ComboBox<String> channels) {
        intervalUnits.getItems().setAll("minute", "hour", "day");
        intervalUnits.setConverter(new StringConverter<>() {
            @Override public String toString(String key) {
                return switch (key == null ? "" : key) {
                    case "hour" -> "小时";
                    case "day" -> "天";
                    default -> "分钟";
                };
            }
            @Override public String fromString(String text) {
                throw new UnsupportedOperationException("间隔单位由选项值确定");
            }
        });
        intervalUnits.setValue("minute");
        policies.getItems().setAll(ExecutionPolicy.values());
        policies.setConverter(new StringConverter<>() {
            @Override public String toString(ExecutionPolicy policy) {
                if (policy == null) return "";
                return policy == ExecutionPolicy.UNTIL_CONDITION ? "目标验证完成后停用" : "持续执行";
            }
            @Override public ExecutionPolicy fromString(String text) {
                throw new UnsupportedOperationException("执行策略由选项值确定");
            }
        });
        channels.getItems().setAll(TaskNotificationChannel.ORDERED_CHANNELS);
        channels.setConverter(new StringConverter<>() {
            @Override public String toString(String key) { return TaskNotificationChannel.displayLabel(key); }
            @Override public String fromString(String text) {
                throw new UnsupportedOperationException("通知渠道由选项值确定");
            }
        });
        channels.setValue(TaskNotificationChannel.NONE);
    }

    record RuntimeFields(Label state, Label trigger, Label next, Label nextSub,
                         Label last, Label lastBadge, Label runs) {
        void render(Task task) {
            state.setText(switch (task.runtimeState()) {
                case RUNNING -> task.enabled() ? "运行中" : "正在停止…";
                case QUEUED -> "排队中";
                case ENABLED -> "已启用";
                case PAUSED -> "已暂停";
                case BUILTIN -> "常驻运行";
            });
            trigger.setText(task.describeTrigger());
            if (task.runtimeState() == RuntimeState.RUNNING) {
                next.setText("执行中…"); nextSub.setText("");
            } else if (!task.enabled()) {
                next.setText("—"); nextSub.setText("");
            } else {
                next.setText(task.nextFireTime() == null ? "等待调度" : task.nextFireTime().format(NEXT));
                nextSub.setText(task.nextFireTime() == null ? "" : ScheduleTaskCell.relative(task.nextFireTime()));
            }
            boolean hasLast = !task.lastRunTime().isBlank();
            last.setText(hasLast ? task.lastRunTime() : "—");
            lastBadge.getStyleClass().removeAll("jc-badge-ok", "jc-badge-fail", "jc-badge-stopped");
            lastBadge.setVisible(hasLast);
            lastBadge.setManaged(hasLast);
            if (hasLast) {
                var result = task.history().isEmpty() ? null : task.history().getFirst().taskResult();
                lastBadge.setText(ScheduleOutcomePresentation.label(task.executionStatus(), result));
                lastBadge.getStyleClass().add(task.executionStatus() == ScheduleExecutionStatus.CANCELLED
                        ? "jc-badge-stopped" : ScheduleOutcomePresentation.runCompleted(task.executionStatus())
                        ? result != null && result.outcome() == TaskOutcome.VERIFIED_COMPLETE
                                ? "jc-badge-ok" : "jc-badge-stopped"
                        : "jc-badge-fail");
            }
            runs.setText(task.runCount() + " / " + task.failCount());
        }
    }
}
