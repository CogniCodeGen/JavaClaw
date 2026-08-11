package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.application.schedule.ScheduleApplicationService.RuntimeState;
import com.javaclaw.application.schedule.ScheduleApplicationService.SaveCommand;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import com.javaclaw.task.TaskNotificationChannel;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;

/** 定时任务详情 Controller：只维护 FXML 表单与页面状态，不访问运行时或数据库。 */
public final class ScheduleDetailController implements AutoCloseable {

    private static final DateTimeFormatter NEXT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @FXML private VBox root;
    @FXML private VBox builtinPanel;
    @FXML private Label builtinName;
    @FXML private Label builtinDescription;
    @FXML private Label builtinTrigger;
    @FXML private Label builtinSource;
    @FXML private Label builtinNote;
    @FXML private VBox editablePanel;
    @FXML private TextField nameField;
    @FXML private Label stateLabel;
    @FXML private ToggleSwitch enabledToggle;
    @FXML private Label triggerStat;
    @FXML private Label nextStat;
    @FXML private Label nextSub;
    @FXML private Label lastStat;
    @FXML private Label lastBadge;
    @FXML private Label runsStat;
    @FXML private ToggleGroup triggerGroup;
    @FXML private ToggleButton onceButton;
    @FXML private ToggleButton intervalButton;
    @FXML private ToggleButton dailyButton;
    @FXML private ToggleButton cronButton;
    @FXML private VBox onceFields;
    @FXML private TextField onceDateField;
    @FXML private TextField onceTimeField;
    @FXML private VBox intervalFields;
    @FXML private TextField intervalValueField;
    @FXML private ComboBox<String> intervalUnitCombo;
    @FXML private VBox dailyFields;
    @FXML private TextField dailyTimeField;
    @FXML private VBox cronFields;
    @FXML private TextField cronField;
    @FXML private Label cronHint;
    @FXML private TextArea promptArea;
    @FXML private ToggleSwitch notifyToggle;
    @FXML private ComboBox<String> channelCombo;
    @FXML private ToggleSwitch authorizeToggle;
    @FXML private ListView<com.javaclaw.application.schedule.ScheduleApplicationService.History> historyList;
    @FXML private Label historyPlaceholder;
    @FXML private Button deleteButton;

    private final ScheduleHistoryCellFactory historyCells;
    private final ScheduleDetailViewModel viewModel = new ScheduleDetailViewModel();
    private Consumer<Boolean> toggleAction = ignored -> { };
    private Runnable deleteAction = () -> { };
    private Runnable changedAction = () -> { };
    private boolean suppressEvents;
    private boolean draft;

    public ScheduleDetailController(ScheduleHistoryCellFactory historyCells) {
        this.historyCells = Objects.requireNonNull(historyCells, "historyCells");
    }

    @FXML
    private void initialize() {
        intervalUnitCombo.getItems().setAll("分钟", "小时", "天");
        for (String key : TaskNotificationChannel.ORDERED_CHANNELS) {
            channelCombo.getItems().add(TaskNotificationChannel.displayLabel(key));
        }
        historyList.setCellFactory(ignored -> historyCells.create());
        enabledToggle.selectedProperty().addListener((ignored, previous, enabled) -> {
            if (!suppressEvents) toggleAction.accept(enabled);
        });
        notifyToggle.selectedProperty().addListener((ignored, previous, enabled) -> {
            channelCombo.setDisable(!enabled);
            if (!suppressEvents) changedAction.run();
        });
        cronField.textProperty().addListener((ignored, previous, value) -> updateCronHint());
    }

    void configure(Consumer<Boolean> onToggle, Runnable onDelete, Runnable onChanged) {
        toggleAction = onToggle == null ? ignored -> { } : onToggle;
        deleteAction = onDelete == null ? () -> { } : onDelete;
        changedAction = onChanged == null ? () -> { } : onChanged;
    }

    void show(Task task, boolean draft) {
        this.draft = draft;
        viewModel.taskProperty().set(Objects.requireNonNull(task, "task"));
        root.setVisible(true);
        root.setManaged(true);
        showNode(builtinPanel, task.builtin());
        showNode(editablePanel, !task.builtin());
        deleteButton.setVisible(!task.builtin());
        deleteButton.setManaged(!task.builtin());
        historyList.getItems().setAll(task.history());
        historyPlaceholder.setVisible(task.history().isEmpty());
        historyPlaceholder.setManaged(task.history().isEmpty());
        historyList.setVisible(!task.history().isEmpty());
        historyList.setManaged(!task.history().isEmpty());
        if (task.builtin()) applyBuiltin(task);
        else applyEditable(task);
    }

    void clear() {
        viewModel.taskProperty().set(null);
        historyList.getItems().clear();
        root.setVisible(false);
        root.setManaged(false);
    }

    SaveCommand command() {
        Task task = Objects.requireNonNull(viewModel.taskProperty().get(), "没有选中的定时任务");
        String once = (onceDateField.getText().strip() + " " + onceTimeField.getText().strip()).strip();
        return new SaveCommand(task.id(), nameField.getText(), task.description(),
                viewModel.triggerTypeProperty().get(), positiveInt(intervalValueField.getText()),
                unitKey(intervalUnitCombo.getValue()), dailyTimeField.getText(), cronField.getText(),
                once, promptArea.getText(), enabledToggle.isSelected(), task.version(),
                notifyToggle.isSelected(), TaskNotificationChannel.fromLabel(channelCombo.getValue()),
                authorizeToggle.isSelected(), draft);
    }

    Task task() { return viewModel.taskProperty().get(); }

    void refreshClock() {
        Task task = viewModel.taskProperty().get();
        if (task != null && !task.builtin()) applyRuntime(task);
    }

    void requestNameFocus() {
        nameField.requestFocus();
        nameField.selectAll();
    }

    @FXML
    private void triggerRequested(ActionEvent event) {
        ToggleButton source = (ToggleButton) event.getSource();
        if (!source.isSelected()) {
            source.setSelected(true);
            return;
        }
        viewModel.triggerTypeProperty().set(String.valueOf(source.getUserData()));
        updateTriggerFields();
        changedAction.run();
    }

    @FXML
    private void cronPresetRequested(ActionEvent event) {
        cronField.setText(String.valueOf(((Button) event.getSource()).getUserData()));
    }

    @FXML private void deleteRequested() { deleteAction.run(); }

    private void applyBuiltin(Task task) {
        builtinName.setText(task.name());
        builtinDescription.setText(task.description());
        builtinTrigger.setText(task.describeTrigger());
        builtinSource.setText(task.sourceModule().isBlank() ? "—" : task.sourceModule());
        builtinNote.setText(task.manuallyRunnable()
                ? "这是代码内部的周期性机制，由系统自动运行，不可编辑、停用或删除；可从页脚手动触发一次。"
                : "这是代码内部的周期性机制，由系统自动运行，不可编辑、停用、删除或手动触发。");
    }

    private void applyEditable(Task task) {
        suppressEvents = true;
        try {
            nameField.setText(task.name());
            enabledToggle.setSelected(task.enabled());
            viewModel.triggerTypeProperty().set(task.triggerType().isBlank()
                    ? "interval" : task.triggerType());
            selectTrigger(viewModel.triggerTypeProperty().get());
            String[] once = splitOnce(task.onceDateTime());
            onceDateField.setText(once[0].isBlank() ? "2026-06-10" : once[0]);
            onceTimeField.setText(once[1].isBlank() ? "08:30" : once[1]);
            intervalValueField.setText(String.valueOf(Math.max(1,
                    task.intervalValue() > 0 ? task.intervalValue() : task.intervalMinutes())));
            intervalUnitCombo.setValue(unitLabel(task.intervalUnit()));
            dailyTimeField.setText(task.dailyTime().isBlank() ? "09:00" : task.dailyTime());
            cronField.setText(task.cronExpression());
            promptArea.setText(task.prompt());
            notifyToggle.setSelected(task.notifyEnabled());
            channelCombo.setValue(TaskNotificationChannel.displayLabel(task.notifyChannel()));
            channelCombo.setDisable(!task.notifyEnabled());
            authorizeToggle.setSelected(task.unattendedToolsAuthorized());
            updateTriggerFields();
            updateCronHint();
            applyRuntime(task);
        } finally {
            suppressEvents = false;
        }
    }

    private void applyRuntime(Task task) {
        stateLabel.setText(switch (task.runtimeState()) {
            case RUNNING -> task.enabled() ? "运行中" : "正在停止…";
            case QUEUED -> "排队中";
            case ENABLED -> "已启用";
            case PAUSED -> "已暂停";
            case BUILTIN -> "常驻运行";
        });
        triggerStat.setText(task.describeTrigger());
        if (task.runtimeState() == RuntimeState.RUNNING) {
            nextStat.setText("执行中…"); nextSub.setText("");
        } else if (!task.enabled()) {
            nextStat.setText("—"); nextSub.setText("");
        } else {
            nextStat.setText(task.nextFireTime() == null ? "等待调度" : task.nextFireTime().format(NEXT));
            nextSub.setText(task.nextFireTime() == null ? "" : ScheduleTaskCell.relative(task.nextFireTime()));
        }
        boolean hasLast = !task.lastRunTime().isBlank();
        lastStat.setText(hasLast ? task.lastRunTime() : "—");
        lastBadge.getStyleClass().removeAll("jc-badge-ok", "jc-badge-fail", "jc-badge-stopped");
        showNode(lastBadge, hasLast);
        if (hasLast) {
            lastBadge.setText(task.lastRunStatus());
            lastBadge.getStyleClass().add("成功".equals(task.lastRunStatus()) ? "jc-badge-ok"
                    : "已取消".equals(task.lastRunStatus()) ? "jc-badge-stopped" : "jc-badge-fail");
        }
        runsStat.setText(task.runCount() + " / " + task.failCount());
    }

    private void selectTrigger(String type) {
        ToggleButton button = switch (type) {
            case "once" -> onceButton;
            case "daily" -> dailyButton;
            case "cron" -> cronButton;
            default -> intervalButton;
        };
        triggerGroup.selectToggle(button);
    }

    private void updateTriggerFields() {
        String selected = viewModel.triggerTypeProperty().get();
        showNode(onceFields, "once".equals(selected));
        showNode(intervalFields, "interval".equals(selected));
        showNode(dailyFields, "daily".equals(selected));
        showNode(cronFields, "cron".equals(selected));
    }

    private void updateCronHint() {
        String expression = cronField.getText() == null ? "" : cronField.getText().strip();
        String validity = expression.isEmpty() ? "" : org.quartz.CronExpression
                .isValidExpression(expression) ? "  ·  ✓ 表达式有效" : "  ·  ✗ 表达式非法";
        cronHint.setText("Quartz 6 段：秒 分 时 日 月 周（日/周二选一用 ?）" + validity);
    }

    private static String[] splitOnce(String value) {
        String[] result = {"", ""};
        if (value != null && value.contains(" ")) {
            String[] parts = value.split(" ", 2);
            result[0] = parts[0]; result[1] = parts[1];
        }
        return result;
    }

    private static int positiveInt(String value) {
        try { return Math.max(1, Integer.parseInt(value.strip())); }
        catch (RuntimeException ignored) { return 1; }
    }

    private static String unitLabel(String key) {
        return "hour".equals(key) ? "小时" : "day".equals(key) ? "天" : "分钟";
    }

    private static String unitKey(String label) {
        return "小时".equals(label) ? "hour" : "天".equals(label) ? "day" : "minute";
    }

    private static void showNode(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    @Override
    public void close() {
        historyList.setCellFactory(null);
        historyList.getItems().clear();
        toggleAction = ignored -> { };
        deleteAction = () -> { };
        changedAction = () -> { };
    }
}
