package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionJobCancellationTest {
    @TempDir
    Path temporary;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.systemUTC();
    private H2Database database;
    private ExtensionJobService jobs;
    private ExtensionJobService.ClaimedJob active;

    @BeforeEach
    void createRecordedWorkUnit() throws Exception {
        database = new H2Database(temporary.resolve("data-v6"));
        database.initialize();
        jobs = new ExtensionJobService(database, json, clock);
        var core = new CoreCommandService(database, json, clock);
        var payload = new CoreRpcContracts.WorkspaceCreatePayload("cancel", temporary.resolve("workspace"));
        var workspace = core.createWorkspace(
                CommandIdentity.from("workspace/create", new WriteCommand("workspace", 0, json.encode(payload)), json),
                payload.name(),
                payload.root());
        var submission = new ExtensionJobSubmission(
                new ExtensionId("com.javaclaw.memory"),
                workspace.id(),
                "learning",
                "definition",
                1,
                json.parse("{}"),
                json.parse("{}"));
        jobs.submit(json.encode(submission), new ExtensionJobMutation("submit", 0), () -> submission);
        active = jobs.recordIntent(jobs.claimNext().orElseThrow(), new ExtensionJobWorkUnit("unit", json.parse("{}")));
    }

    @Test
    void 活动单元取消不抢占执行版本且保留已提交副作用回执() {
        CancellationSource signal = new CancellationSource();
        jobs.bindCancellation(active.job().id(), signal);
        var mutation = new ExtensionJobMutation("cancel", active.job().revision());
        var pending = jobs.cancel(active.job().id(), mutation);
        assertEquals(active.job().revision(), pending.revision());
        assertEquals(ExecutionState.RUNNING, pending.state());
        assertTrue(signal.isCancelled());
        assertEquals(pending, jobs.cancel(active.job().id(), mutation));
        var result = jobs.complete(
                active,
                new ExtensionJobStepResult(
                        json.parse("{\"effect\":true}"),
                        json.parse("{\"completed\":1}"),
                        ExecutionState.RUNNING,
                        Optional.empty(),
                        Optional.of("durable-effect")));
        assertEquals(ExecutionState.CANCELLED, result.state());
        assertEquals(
                "durable-effect",
                jobs.read(result.id()).units().getFirst().effectReceiptKey().orElseThrow());
        assertFalse(jobs.claimNext().isPresent());
        jobs.unbindCancellation(active.job().id(), signal);
    }

    @Test
    void 取消提交后进程重启会恢复相同Unit并在执行前观察到取消() {
        jobs.cancel(
                active.job().id(),
                new ExtensionJobMutation("cancel", active.job().revision()));
        var restarted = new ExtensionJobService(database, json, clock);
        restarted.recoverOutbox();
        var recovered = restarted.claimNext().orElseThrow();
        assertEquals(active.unit(), recovered.unit());
        CancellationSource signal = new CancellationSource();
        restarted.bindCancellation(recovered.job().id(), signal);
        assertTrue(signal.isCancelled());
        restarted.unbindCancellation(recovered.job().id(), signal);
    }

    @Test
    void 结果未知失败不能被取消意图覆盖为成功或自动重试() {
        jobs.cancel(
                active.job().id(),
                new ExtensionJobMutation("cancel", active.job().revision()));
        var failed = jobs.fail(active, "OUTCOME_UNKNOWN");
        assertEquals(ExecutionState.FAILED, failed.state());
        assertEquals("OUTCOME_UNKNOWN", failed.errorCode().orElseThrow());
        assertFalse(jobs.claimNext().isPresent());
    }
}
