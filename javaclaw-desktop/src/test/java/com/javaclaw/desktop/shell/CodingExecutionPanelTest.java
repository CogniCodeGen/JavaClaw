package com.javaclaw.desktop.shell;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javafx.scene.control.TextArea;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.client.extension.CodingExecutionPoller;
import com.javaclaw.client.extension.CodingTranscriptFormatter;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingExecutionPanelTest {
    @Test
    void 进度侧栏使用共享转录正文并保留终态尾页且始终只读() {
        FxTestSupport.run(() -> {
            CodingExecutionPanel panel = new CodingExecutionPanel(
                    poller -> CompletableFuture.completedFuture(new CodingExecutionPoller.Poll(List.of(), false)));
            var summary = new CodingResults.ExecutionSummary(
                    "run-1", "command_run", TurnId.random(), "FAILED", 4, Optional.of(1));
            var fact = new CodingTranscriptFormatter.Fact("命令 · FAILED · run-1", "test failed\n退出码：1");
            var snapshot = new CodingExecutionPoller.Snapshot(summary, fact, "test failed", true, 4, false);
            panel.render(new DesktopCodingOutputController.State(List.of(snapshot), Optional.empty()));
            TextArea output = (TextArea) panel.lookup("#codingExecutionOutput");
            assertEquals(fact.title() + "\n" + fact.body() + "\n\n", output.getText());
            assertFalse(output.isEditable());
            assertTrue(panel.isManaged());
            panel.render(new DesktopCodingOutputController.State(List.of(), Optional.empty()));
            assertFalse(panel.isManaged());
            assertEquals("", output.getText());
        });
    }
}
