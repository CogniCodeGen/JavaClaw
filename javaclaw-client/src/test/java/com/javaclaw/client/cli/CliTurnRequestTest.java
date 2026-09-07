package com.javaclaw.client.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTurnRequestTest {
    private static final String THREAD = "00000000-0000-0000-0000-000000000001";

    @Test
    void 独立选择进入SDK契约且保留用户可重试幂等键() {
        CliTurnRequest request = CliTurnRequest.parse(List.of(
                "turn-start",
                THREAD,
                "任务",
                "--role",
                "worker",
                "2",
                "--provider",
                "local",
                "3",
                "model",
                "--permission",
                "readonly",
                "4",
                "--approval",
                "EVERY_CALL",
                "--reasoning",
                "HIGH",
                "--budget",
                "1000",
                "500",
                "10",
                "2",
                "60",
                "--capabilities",
                "read_file,search",
                "--idempotency-key",
                "retry"));
        assertEquals(
                "worker", request.payload().execution().role().orElseThrow().id());
        assertEquals(
                "model", request.payload().execution().provider().orElseThrow().model());
        assertEquals(
                "readonly",
                request.payload().execution().permissionProfile().orElseThrow().id());
        assertEquals(
                ApprovalPolicy.EVERY_CALL,
                request.payload().execution().approvalPolicy().orElseThrow());
        assertEquals(2, request.payload().execution().budget().orElseThrow().childThreads());
        assertEquals(
                java.time.Duration.ofSeconds(60),
                request.payload().execution().budget().orElseThrow().wallTime());
        assertEquals(
                java.util.Set.of("read_file", "search"),
                request.payload().execution().visibleCapabilities().orElseThrow());
        assertEquals("retry", request.options().idempotencyKey());
        assertEquals(0, request.options().expectedRevision());
    }

    @Test
    void 缺省值交由服务端解析且非法参数本地拒绝() {
        assertTrue(CliTurnRequest.parse(List.of("turn-start", THREAD, "任务"))
                .payload()
                .execution()
                .role()
                .isEmpty());
        assertTrue(CliTurnRequest.parse(List.of("turn-start", THREAD, "任务", "--capabilities", ""))
                .payload()
                .execution()
                .visibleCapabilities()
                .orElseThrow()
                .isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CliTurnRequest.parse(List.of("turn-start")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CliTurnRequest.parse(List.of("turn-start", THREAD, "任务", "--role")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CliTurnRequest.parse(List.of("turn-start", THREAD, "任务", "--unknown")));
    }
}
