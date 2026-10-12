package com.javaclaw.chat;

import com.javaclaw.application.chat.TaskResultDisplay;
import com.javaclaw.framework.api.TaskResult;

/** Keeps live replies and durable transcript recovery on the same display format. */
final class TaskResultPresentation {
    private TaskResultPresentation() {}

    static String append(String reply, TaskResult result) {
        return TaskResultDisplay.append(reply, result);
    }

    static String append(String reply, TaskResult result, String userRequest) {
        return TaskResultDisplay.append(reply, result, userRequest);
    }

    static String format(TaskResult result) {
        return TaskResultDisplay.format(result);
    }

    static String format(TaskResult result, String userRequest) {
        return TaskResultDisplay.format(result, userRequest);
    }
}
