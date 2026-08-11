package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 记忆中心的当前分区、搜索条件和不可变数据快照。 */
final class MemoryViewModel {

    private final StringProperty section = new SimpleStringProperty("overview");
    private final StringProperty query = new SimpleStringProperty("");
    private final ObjectProperty<Snapshot> snapshot = new SimpleObjectProperty<>();
    private final ReadOnlyStringWrapper scaleMain = new ReadOnlyStringWrapper("0 事实 · 0 情景");
    private final ReadOnlyStringWrapper scaleSub = new ReadOnlyStringWrapper("0 实体 · 0 文档");
    private final ReadOnlyStringWrapper degradeText = new ReadOnlyStringWrapper("");

    StringProperty sectionProperty() { return section; }
    StringProperty queryProperty() { return query; }
    ObjectProperty<Snapshot> snapshotProperty() { return snapshot; }
    ReadOnlyStringProperty scaleMainProperty() { return scaleMain.getReadOnlyProperty(); }
    ReadOnlyStringProperty scaleSubProperty() { return scaleSub.getReadOnlyProperty(); }
    ReadOnlyStringProperty degradeTextProperty() { return degradeText.getReadOnlyProperty(); }

    void apply(Snapshot value) {
        snapshot.set(value);
        scaleMain.set(value.facts().size() + " 事实 · " + value.episodes().size() + " 情景");
        scaleSub.set(value.entities().size() + " 实体 · " + value.documents().size() + " 文档");
        degradeText.set(degradeMessage(value));
    }

    private static String degradeMessage(Snapshot snapshot) {
        var state = snapshot.embedding();
        if (!state.degraded()) return "";
        StringBuilder message = new StringBuilder();
        if (!state.healthy()) {
            message.append("嵌入服务不可用：")
                    .append(MemoryUiText.oneLine(state.error(), 88))
                    .append("。事实/情景已降级为纯文本暂存");
        }
        if (state.pendingCount() > 0) {
            if (state.healthy()) {
                message.append("有 ").append(state.pendingCount())
                        .append(" 条降级暂存记忆待回填（嵌入服务已恢复，可立即重嵌入）");
            } else {
                message.append("（").append(state.pendingCount())
                        .append(" 条待嵌入，服务恢复后可重新纳入向量召回与图谱）");
            }
        } else if (!state.healthy()) {
            message.append("，恢复服务后新记忆将带向量入库。");
        }
        return message.toString();
    }
}
