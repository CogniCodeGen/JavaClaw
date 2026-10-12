package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import com.javaclaw.application.memory.MemoryApplicationService.EmbeddingState;
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
            String status = switch (state.status()) {
                case UNCONFIGURED -> "未配置";
                case CHECKING -> "检查中";
                case DEGRADED -> "已降级";
                case UNAVAILABLE -> "不可用";
                case HEALTHY -> "正常";
            };
            message.append("嵌入服务").append(status);
            if (!state.error().isBlank()) message.append("：").append(MemoryUiText.oneLine(state.error(), 88));
            message.append("。事实/情景可保存为纯文本");
        }
        if (state.pendingCount() > 0) {
            if (state.healthy()) {
                message.append("有 ").append(state.pendingCount())
                        .append(" 条降级暂存记忆待回填（嵌入服务正常，可立即重嵌入）");
            } else {
                message.append("（").append(state.pendingCount())
                        .append(" 条待嵌入，").append(embeddingReadyCondition(state))
                        .append("可重新纳入向量召回与图谱）");
            }
        } else if (!state.healthy()) {
            message.append("，").append(embeddingReadyCondition(state)).append("新记忆将带向量入库。");
        }
        return message.toString();
    }

    private static String embeddingReadyCondition(EmbeddingState state) {
        return switch (state.status()) {
            case UNCONFIGURED -> "配置并连接成功后";
            case CHECKING -> "检查通过后";
            default -> "服务恢复后";
        };
    }
}
