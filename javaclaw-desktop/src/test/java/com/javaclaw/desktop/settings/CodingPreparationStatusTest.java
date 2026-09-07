package com.javaclaw.desktop.settings;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingPreparationStatusTest {
    @Test
    void 准备状态单在途且Workspace切换和离页丢弃迟到响应() {
        List<CompletableFuture<CodingResults.ExecutionList>> requests = new ArrayList<>();
        CodingSettingsGateway gateway = (CodingSettingsGateway) Proxy.newProxyInstance(
                CodingSettingsGateway.class.getClassLoader(),
                new Class<?>[] {CodingSettingsGateway.class},
                (proxy, method, args) -> {
                    assertEquals("executions", method.getName());
                    var response = new CompletableFuture<CodingResults.ExecutionList>();
                    requests.add(response);
                    return response;
                });
        FxTestSupport.run(() -> {
            CodingPreparationStatus status = new CodingPreparationStatus(gateway);
            VBox root = new VBox(status.section());
            new Scene(root);
            Label label = (Label) root.lookup("#codingPreparationStatus");
            status.bind(Optional.of(WorkspaceId.random()), true);
            status.poll();
            assertEquals(1, requests.size());
            var next = Optional.of(WorkspaceId.random());
            status.bind(next, true);
            assertEquals(1, requests.size());
            requests.getFirst().complete(result("old", "RUNNING"));
            assertEquals("尚未读取准备状态", label.getText());
            status.poll();
            assertEquals(2, requests.size());
            requests.getLast().complete(result("current", "FAILED"));
            assertTrue(label.getText().contains("FAILED · current · 12 字节 · 退出码 1"));
            status.poll();
            status.bind(next, false);
            requests.getLast().complete(result("late", "COMPLETED"));
            assertEquals("尚未读取准备状态", label.getText());
        });
    }

    private static CodingResults.ExecutionList result(String id, String state) {
        return new CodingResults.ExecutionList(List.of(new CodingResults.ExecutionSummary(
                id, "dependencies_prepare", TurnId.random(), state, 12, Optional.of(1))));
    }
}
