package com.javaclaw.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobCursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputJobRpcContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void input与Job查询复制集合并校验分页() {
        InputJobRpcContracts.InputListPayload inputs =
                new InputJobRpcContracts.InputListPayload(Optional.empty(), false);
        InputJobRpcContracts.JobListPayload jobs = new InputJobRpcContracts.JobListPayload(
                Optional.of(new WorkspaceId(new UUID(1, 1))),
                Optional.of("com.javaclaw.workflow"),
                Set.of(ExecutionState.RUNNING),
                Optional.of(new ExtensionJobCursor(NOW, "job-a")),
                100);

        assertEquals(Optional.empty(), inputs.turnId());
        assertEquals(Set.of(ExecutionState.RUNNING), jobs.states());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputJobRpcContracts.JobListPayload(
                        Optional.empty(), Optional.empty(), Set.of(), Optional.empty(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputJobRpcContracts.JobListPayload(
                        Optional.empty(), Optional.of("bad extension"), Set.of(), Optional.empty(), 1));
    }

    @Test
    void Job详情拒绝混入其他Job工作单元() {
        ExtensionJob job = job("job-a");
        ExtensionExecutionReceipt receipt = ExtensionExecutionReceipt.from(job);
        com.javaclaw.extension.spi.ExtensionJobUnit foreign = new com.javaclaw.extension.spi.ExtensionJobUnit(
                "job-b",
                1,
                "unit",
                new CanonicalPayload("{}"),
                com.javaclaw.extension.spi.ExtensionJobUnitState.INTENT_RECORDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW,
                Optional.empty());

        assertEquals(receipt, new InputJobRpcContracts.JobReadResult(receipt, List.of()).job());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputJobRpcContracts.JobReadResult(
                        receipt, List.of(InputJobRpcContracts.JobUnitSummary.from(foreign))));
    }

    @Test
    void 方法目录声明Input与Job读写类别() {
        NegotiatedCapabilities capabilities = new NegotiatedCapabilities(Set.of(), Set.of());

        assertEquals(
                RpcMethodKind.QUERY,
                MethodCatalog.require("turn/input/list", capabilities).kind());
        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require("turn/input/resolve", capabilities).kind());
        assertEquals(
                RpcMethodKind.QUERY,
                MethodCatalog.require("extension/job/read", capabilities).kind());
        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require("extension/job/cancel", capabilities).kind());
    }

    private static ExtensionJob job(String id) {
        return new ExtensionJob(
                id,
                new ExtensionId("com.javaclaw.workflow"),
                new WorkspaceId(new UUID(1, 1)),
                "workflow",
                "definition",
                1,
                new CanonicalPayload("{}"),
                ExecutionState.QUEUED,
                1,
                new CanonicalPayload("{}"),
                1,
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW);
    }
}
