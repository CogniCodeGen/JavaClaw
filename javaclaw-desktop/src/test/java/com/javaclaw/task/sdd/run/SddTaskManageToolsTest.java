package com.javaclaw.task.sdd.run;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.task.SddTaskApplicationService;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SddTaskManageToolsTest {

    @Test
    void blankTaskIdsAreReturnedAsToolErrorsInsteadOfEscapingTheBridge() {
        SddTaskManageTools tools = new SddTaskManageTools(
                ToolCallOrigin.UNKNOWN, new ValidatingTaskService());

        List.of(
                tools.taskStatus(""),
                tools.taskPause(" "),
                tools.taskResume(null),
                tools.taskCancel("")
        ).forEach(result -> {
            assertTrue(result.contains("[失败]"), result);
            assertTrue(result.contains("任务 ID不能为空"), result);
        });
    }

    private static final class ValidatingTaskService implements SddTaskApplicationService {
        @Override public Snapshot snapshot() { return new Snapshot(List.of()); }
        @Override public Task require(String taskId) { throw invalidId(); }
        @Override public String generateTitle(String description) { throw new UnsupportedOperationException(); }
        @Override public Task create(CreateCommand command) { throw new UnsupportedOperationException(); }
        @Override public void start(String taskId, String completionStamp) { throw new UnsupportedOperationException(); }
        @Override public void resume(String taskId, String completionStamp) { throw invalidId(); }
        @Override public void pause(String taskId) { throw invalidId(); }
        @Override public void cancel(String taskId) { throw invalidId(); }
        @Override public void delete(String taskId) { throw new UnsupportedOperationException(); }
        @Override public Task updateTokenBudget(String taskId, long newBudget) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<OpenSpecChange> specification(String taskId) { return Optional.empty(); }
        @Override public AutoCloseable observe(EventListener listener) { return () -> { }; }
        @Override public void suspendForRuntimeTransition() { }

        private static ValidationException invalidId() {
            return new ValidationException("任务 ID不能为空");
        }
    }
}
