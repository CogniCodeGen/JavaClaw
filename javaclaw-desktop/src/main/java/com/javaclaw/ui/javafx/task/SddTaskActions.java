package com.javaclaw.ui.javafx.task;

import com.javaclaw.application.task.SddTaskApplicationService.Task;
import javafx.scene.control.Button;

import java.util.List;

/** 按结构化任务状态渲染操作按钮；不提交任务，也不从日志推测状态。 */
record SddTaskActions(Button start, Button pause, Button cancel, Button resume,
                      Button budget, Button resumeBudget, Button rerun, Button delete) {
    void render(Task task) {
        for (Button button : List.of(start, pause, cancel, resume, budget, resumeBudget, rerun, delete)) {
            button.setVisible(false);
            button.setManaged(false);
        }
        if (task == null) return;
        switch (task.state()) {
            case PENDING -> show(start, delete);
            case RUNNING -> show(budget, pause, cancel);
            case NEEDS_HUMAN -> show(resumeBudget, resume, delete);
            case PAUSED -> show(resume, budget, cancel, delete);
            case COMPLETED, FAILED, CANCELLED -> show(rerun, delete);
        }
    }

    private static void show(Button... buttons) {
        for (Button button : buttons) {
            button.setVisible(true);
            button.setManaged(true);
        }
    }
}
