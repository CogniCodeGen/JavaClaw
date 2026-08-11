package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.RunStatus;

/** 工作流展示文案与稳定 CSS 映射。 */
final class WorkflowLabels {

    private WorkflowLabels() {
    }

    static String nodeName(NodeType type) {
        return switch (type) {
            case START -> "开始";
            case END -> "结束";
            case AGENT -> "智能体";
            case TOOL -> "本地工具";
            case CONDITION -> "条件分支";
            case TRANSFORM -> "状态转换";
            case HUMAN_INPUT -> "人工输入";
            case OUTPUT -> "输出";
            case SYSTEM -> "系统阶段";
        };
    }

    static String nodeGlyph(NodeType type) {
        return switch (type) {
            case START -> "▶";
            case END -> "■";
            case AGENT -> "✦";
            case TOOL -> "⌁";
            case CONDITION -> "◇";
            case TRANSFORM -> "⇄";
            case HUMAN_INPUT -> "?";
            case OUTPUT -> "↗";
            case SYSTEM -> "◆";
        };
    }

    static String runStatus(RunStatus status) {
        return switch (status) {
            case CREATED -> "已创建";
            case RUNNING -> "运行中";
            case WAITING_INPUT -> "待输入";
            case PAUSED -> "已暂停";
            case RECOVERY_REQUIRED -> "待恢复";
            case COMPLETED -> "已完成";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
        };
    }

    static String runStyle(RunStatus status) {
        return switch (status) {
            case RUNNING -> "jc-badge-running";
            case WAITING_INPUT, RECOVERY_REQUIRED -> "jc-badge-amber";
            case COMPLETED -> "jc-badge-ok";
            case FAILED -> "jc-badge-failed";
            default -> "jc-badge-stopped";
        };
    }
}
