package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 详情区当前任务和触发方式状态；不持有应用服务。 */
public final class ScheduleDetailViewModel {
    private final ObjectProperty<Task> task = new SimpleObjectProperty<>();
    private final StringProperty triggerType = new SimpleStringProperty("interval");

    public ObjectProperty<Task> taskProperty() { return task; }
    public StringProperty triggerTypeProperty() { return triggerType; }
}
