package com.javaclaw.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionPreviewContractsTest {
    @Test
    void 阻塞结果冻结集合且不允许无模型结果宣称就绪() {
        List<ExecutionBlocker> blockers = new ArrayList<>();
        blockers.add(new ExecutionBlocker(ExecutionBlocker.Code.MODEL_REQUIRED, "请选择模型"));
        ExecutionPreview preview = new ExecutionPreview(
                Optional.empty(), Optional.empty(), Optional.empty(), false, false, List.of(), blockers);
        blockers.clear();

        assertFalse(preview.ready());
        assertEquals(1, preview.blockers().size());
        assertThrows(
                UnsupportedOperationException.class, () -> preview.blockers().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionPreview(
                        Optional.empty(), Optional.empty(), Optional.empty(), false, false, List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionPreview(
                        Optional.of(new AgentRoleRef("default", 1)),
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        false,
                        List.of(),
                        List.of()));
        assertThrows(NullPointerException.class, () -> new ExecutionBlocker(null, "说明"));
        assertThrows(
                IllegalArgumentException.class, () -> new ExecutionBlocker(ExecutionBlocker.Code.MODEL_REQUIRED, " "));
    }
}
