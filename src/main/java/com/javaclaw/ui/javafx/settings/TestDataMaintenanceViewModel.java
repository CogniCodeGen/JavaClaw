package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.Candidate;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.CleanupResult;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.ScanResult;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** 历史测试数据维护页的纯 JavaFX 状态。 */
public final class TestDataMaintenanceViewModel {

    private final ObservableList<Candidate> candidates = FXCollections.observableArrayList();
    private final StringProperty status = new SimpleStringProperty("尚未扫描");
    private final StringProperty placeholder = new SimpleStringProperty("尚未扫描");
    private final BooleanProperty busy = new SimpleBooleanProperty();

    public void scanning() {
        status.set("正在扫描…");
    }

    public void apply(ScanResult result) {
        candidates.setAll(result.candidates());
        placeholder.set(result.candidates().isEmpty() ? "未发现历史测试目录" : "");
        status.set(result.candidates().isEmpty()
                ? "未发现带 JavaClaw 标记的可清理目录"
                : "发现 " + result.candidates().size() + " 个 JavaClaw 测试目录，共 "
                        + SettingsValueFormatter.humanReadableBytes(result.totalBytes()));
    }

    public void awaitingConfirmation() {
        status.set("等待确认…");
    }

    public void cleanupCancelled() {
        status.set("已取消清理");
    }

    public void cleaned(CleanupResult result) {
        candidates.clear();
        placeholder.set("已清理");
        status.set("已清理 " + result.deleted() + " 个历史测试目录");
    }

    public void failed(String message) {
        status.set("操作失败：" + message);
    }

    public ObservableList<Candidate> candidates() { return candidates; }
    public StringProperty statusProperty() { return status; }
    public StringProperty placeholderProperty() { return placeholder; }
    public BooleanProperty busyProperty() { return busy; }
}
