package com.javaclaw.desktop.component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** 展示执行、恢复和状态变化的只读时间线。 */
public final class ExecutionTimeline extends VBox {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    /** 创建空时间线。 */
    public ExecutionTimeline() {
        getStyleClass().add("platform-timeline");
    }

    /**
     * 替换全部时间线条目。
     *
     * @param entries 按展示顺序排列的条目
     */
    public void setEntries(List<Entry> entries) {
        getChildren()
                .setAll(Objects.requireNonNull(entries, "entries").stream()
                        .map(this::createEntry)
                        .toList());
    }

    private HBox createEntry(Entry entry) {
        Entry checked = Objects.requireNonNull(entry, "entry");
        Label marker = new Label("●");
        marker.getStyleClass().add("platform-timeline-marker");
        Label title = new Label(checked.title());
        title.getStyleClass().add("platform-detail-title");
        Label detail = new Label(checked.detail());
        detail.setWrapText(true);
        detail.getStyleClass().add("platform-detail-text");
        Label time = new Label(TIME_FORMAT.format(checked.at().atZone(ZoneId.systemDefault())));
        time.getStyleClass().add("platform-timeline-time");
        HBox row = new HBox(10, marker, new VBox(3, title, detail, time));
        row.getStyleClass().add("platform-timeline-entry");
        return row;
    }

    /**
     * 一条执行状态记录。
     *
     * @param at 发生时间，不能为空
     * @param title 简短状态
     * @param detail 简单说明
     */
    public record Entry(Instant at, String title, String detail) {
        /** 校验时间线内容。 */
        public Entry {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
