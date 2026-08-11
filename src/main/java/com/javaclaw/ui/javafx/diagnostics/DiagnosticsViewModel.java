package com.javaclaw.ui.javafx.diagnostics;

import com.javaclaw.application.diagnostics.DiagnosticsApplicationService.TimeRange;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

/** 诊断页面唯一的 JavaFX 状态容器，不持有应用服务或基础设施对象。 */
final class DiagnosticsViewModel {

    static final String ALL_EVENTS = "全部";
    static final int RESULT_LIMIT = 2_000;

    private final ObservableList<RangeChoice> ranges = FXCollections.observableArrayList(
            new RangeChoice("最近 15 分钟", TimeRange.LAST_15_MINUTES),
            new RangeChoice("最近 1 小时", TimeRange.LAST_HOUR),
            new RangeChoice("最近 24 小时", TimeRange.LAST_24_HOURS),
            new RangeChoice("全部", TimeRange.ALL));
    private final ObservableList<String> eventTypes = FXCollections.observableArrayList(
            ALL_EVENTS, "tool_call", "tool_result", "model_call", "error");
    private final ObservableList<String> results = FXCollections.observableArrayList();
    private final ObjectProperty<RangeChoice> selectedRange =
            new SimpleObjectProperty<>(ranges.get(2));
    private final ObjectProperty<String> selectedEvent =
            new SimpleObjectProperty<>(ALL_EVENTS);
    private final StringProperty agent = new SimpleStringProperty("");
    private final StringProperty keyword = new SimpleStringProperty("");
    private final StringProperty summary =
            new SimpleStringProperty("请点击「查询」加载事件");
    private final StringProperty error = new SimpleStringProperty("");
    private final BooleanProperty querying = new SimpleBooleanProperty(false);
    private final BooleanProperty exporting = new SimpleBooleanProperty(false);

    ObservableList<RangeChoice> ranges() { return ranges; }
    ObservableList<String> eventTypes() { return eventTypes; }
    ObservableList<String> results() { return results; }
    ObjectProperty<RangeChoice> selectedRangeProperty() { return selectedRange; }
    ObjectProperty<String> selectedEventProperty() { return selectedEvent; }
    StringProperty agentProperty() { return agent; }
    StringProperty keywordProperty() { return keyword; }
    StringProperty summaryProperty() { return summary; }
    StringProperty errorProperty() { return error; }
    BooleanProperty queryingProperty() { return querying; }
    BooleanProperty exportingProperty() { return exporting; }

    void showResults(List<String> lines) {
        results.setAll(lines);
        error.set("");
        summary.set("共 " + lines.size() + " 条事件（上限 " + RESULT_LIMIT + "）");
    }

    void showQueryFailure(Throwable failure) {
        results.clear();
        showFailure("查询失败", failure);
    }

    void showExportSuccess(String target, long bytes) {
        error.set("");
        summary.set("诊断包已导出 · " + target + " （" + (bytes / 1024) + " KB）");
    }

    void showExportFailure(Throwable failure) {
        showFailure("导出失败", failure);
    }

    private void showFailure(String prefix, Throwable failure) {
        String detail = failure == null || failure.getMessage() == null
                || failure.getMessage().isBlank()
                ? "未知错误" : failure.getMessage();
        error.set(detail);
        summary.set(prefix + "：" + detail);
    }

    record RangeChoice(String label, TimeRange value) {
        RangeChoice {
            if (label == null || label.isBlank()) throw new IllegalArgumentException("label");
            if (value == null) throw new NullPointerException("value");
        }

        @Override
        public String toString() { return label; }
    }
}
