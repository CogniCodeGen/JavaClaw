package com.javaclaw.server.rpc;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobPage;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ExtensionJobService;
import com.javaclaw.server.persistence.InputRequestService;

/** InputRequest 与 Extension Job Protocol v3 方法的薄 RPC 映射。 */
public final class InputJobRpcHandlers {
    private final InputRequestService inputs;
    private final ExtensionJobService jobs;
    private final CanonicalJson json;

    /**
     * 创建 handlers。
     *
     * @param inputs Turn 用户输入用例
     * @param jobs Extension Job 用例
     * @param json 共享规范 JSON codec
     */
    public InputJobRpcHandlers(InputRequestService inputs, ExtensionJobService jobs, CanonicalJson json) {
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册 Input 与 Job 方法；组合根可将本 registrar 作为一个明确批次接入。
     *
     * @param builder Router Builder
     */
    public void register(RpcRouter.Builder builder) {
        Objects.requireNonNull(builder, "builder")
                .register("turn/input/list", this::listInputs)
                .register("turn/input/resolve", this::resolveInput)
                .register("extension/job/list", this::listJobs)
                .register("extension/job/read", this::readJob)
                .register("extension/job/pause", this::pauseJob)
                .register("extension/job/resume", this::resumeJob)
                .register("extension/job/cancel", this::cancelJob);
    }

    private com.javaclaw.api.CanonicalPayload listInputs(com.javaclaw.api.CanonicalPayload params) {
        InputJobRpcContracts.InputListPayload payload =
                json.decode(params, InputJobRpcContracts.InputListPayload.class);
        return json.encode(
                new InputJobRpcContracts.InputListResult(inputs.list(payload.turnId(), payload.includeResolved())));
    }

    private com.javaclaw.api.CanonicalPayload resolveInput(com.javaclaw.api.CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        InputJobRpcContracts.InputResolvePayload payload =
                json.decode(command.payload(), InputJobRpcContracts.InputResolvePayload.class);
        return json.encode(inputs.resolve(CommandIdentity.from("turn/input/resolve", command, json), payload));
    }

    private com.javaclaw.api.CanonicalPayload listJobs(com.javaclaw.api.CanonicalPayload params) {
        InputJobRpcContracts.JobListPayload payload = json.decode(params, InputJobRpcContracts.JobListPayload.class);
        Optional<ExtensionId> extensionId = payload.extensionId().map(ExtensionId::new);
        ExtensionJobPage page =
                jobs.page(payload.workspaceId(), extensionId, payload.states(), payload.after(), payload.limit());
        return json.encode(new InputJobRpcContracts.JobListResult(
                page.jobs().stream().map(ExtensionExecutionReceipt::from).toList(), page.nextCursor()));
    }

    private com.javaclaw.api.CanonicalPayload readJob(com.javaclaw.api.CanonicalPayload params) {
        InputJobRpcContracts.JobReadPayload payload = json.decode(params, InputJobRpcContracts.JobReadPayload.class);
        return json.encode(jobs.read(payload.jobId()));
    }

    private com.javaclaw.api.CanonicalPayload pauseJob(com.javaclaw.api.CanonicalPayload params) {
        return mutate("extension/job/pause", params);
    }

    private com.javaclaw.api.CanonicalPayload resumeJob(com.javaclaw.api.CanonicalPayload params) {
        return mutate("extension/job/resume", params);
    }

    private com.javaclaw.api.CanonicalPayload cancelJob(com.javaclaw.api.CanonicalPayload params) {
        return mutate("extension/job/cancel", params);
    }

    private com.javaclaw.api.CanonicalPayload mutate(String method, com.javaclaw.api.CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        InputJobRpcContracts.JobMutationPayload payload =
                json.decode(command.payload(), InputJobRpcContracts.JobMutationPayload.class);
        CommandIdentity identity = CommandIdentity.from(method, command, json);
        ExtensionJob result =
                switch (method) {
                    case "extension/job/pause" -> jobs.pause(identity, payload.jobId());
                    case "extension/job/resume" -> jobs.resume(identity, payload.jobId());
                    case "extension/job/cancel" -> jobs.cancel(identity, payload.jobId());
                    default -> throw new IllegalArgumentException("unsupported Job mutation method");
                };
        return json.encode(ExtensionExecutionReceipt.from(result));
    }
}
