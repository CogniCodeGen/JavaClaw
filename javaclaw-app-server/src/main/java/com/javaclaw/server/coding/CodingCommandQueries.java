package com.javaclaw.server.coding;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.server.persistence.CodingCommandOutputRepository;
import com.javaclaw.server.persistence.CodingCommandStreamRepository;
import com.javaclaw.server.persistence.CodingExecutionRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;

/** 已验证操作的进度读取；增量页与历史最终页都按原始字节而非字符计数。 */
final class CodingCommandQueries {
    private final CodingPlatform.Dependencies dependencies;
    private final CodingCommandStreamRepository streams;
    private final CodingCommandOutputRepository outputs;
    private final CodingExecutionRepository executions;

    CodingCommandQueries(CodingPlatform.Dependencies dependencies) {
        this.dependencies = dependencies;
        streams = new CodingCommandStreamRepository(
                dependencies.database(), dependencies.attachments(), dependencies.json());
        outputs = new CodingCommandOutputRepository(dependencies.database(), dependencies.json());
        executions = new CodingExecutionRepository(dependencies.database(), dependencies.json());
    }

    CodingResults.ExecutionList list(ExtensionRequest request) {
        dependencies
                .json()
                .decode(request.payload(), com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Empty.class);
        var summaries = executions.list(request.workspaceId(), request.threadId(), request.turnId());
        for (var summary : summaries) {
            dependencies.authority().requireEvidence(summary.turnId(), request.workspaceId());
        }
        return new CodingResults.ExecutionList(summaries);
    }

    CodingResults.Output output(ExtensionRequest request, CodingOperationRepository.Operation operation) {
        var input = dependencies.json().decode(request.payload(), CodingResults.OutputRead.class);
        var stream = streams.find(request.workspaceId(), operation.intent().turnId(), input.resourceId());
        if (stream.isPresent()) {
            var owner = stream.orElseThrow();
            var page = streams.page(owner, input.offsetBytes(), input.maxBytes());
            return new CodingResults.Output(
                    CodingOutputText.decode(streams.prefix(owner, input.offsetBytes(), "stdout"), page.stdout()),
                    CodingOutputText.decode(streams.prefix(owner, input.offsetBytes(), "stderr"), page.stderr()),
                    page.nextOffsetBytes(),
                    page.truncated());
        }
        var recorded = outputs.find(request.workspaceId(), input.resourceId());
        if (recorded.isPresent()) {
            return legacy(request, operation, input, recorded.orElseThrow());
        }
        if (operation.state().equals("PREPARED")
                || operation.state().equals("STARTED")
                || operation.result().isEmpty()
                || dependencies
                        .json()
                        .fieldNames(operation.result().orElseThrow())
                        .contains("errorCode")) {
            if (input.offsetBytes() != 0) {
                throw new IllegalArgumentException("命令尚未保留输出，游标必须为零");
            }
            return new CodingResults.Output("", "", 0, false);
        }
        throw new SecurityException("命令最终输出证据不存在");
    }

    private CodingResults.Output legacy(
            ExtensionRequest request,
            CodingOperationRepository.Operation operation,
            CodingResults.OutputRead input,
            CodingCommandOutputRepository.Output recorded) {
        var scope = AttachmentScope.workspace(request.workspaceId());
        byte[] stdout =
                dependencies.attachments().read(scope, recorded.stdoutDigest()).content();
        byte[] stderr =
                dependencies.attachments().read(scope, recorded.stderrDigest()).content();
        if (stdout.length != recorded.stdoutBytes() || stderr.length != recorded.stderrBytes()) {
            throw new IllegalStateException("命令输出长度与执行证据不一致");
        }
        long size = (long) stdout.length + stderr.length;
        if (input.offsetBytes() > size) {
            throw new IllegalArgumentException("日志游标超过已保留输出");
        }
        long end = Math.min(size, input.offsetBytes() + input.maxBytes());
        return new CodingResults.Output(
                CodingOutputText.slice(stdout, input.offsetBytes(), end),
                CodingOutputText.slice(
                        stderr, Math.max(0, input.offsetBytes() - stdout.length), Math.max(0, end - stdout.length)),
                end,
                end < size || legacyTruncated(operation));
    }

    private boolean legacyTruncated(CodingOperationRepository.Operation operation) {
        if (operation.result().isEmpty()
                || dependencies
                        .json()
                        .fieldNames(operation.result().orElseThrow())
                        .contains("errorCode")) {
            return false;
        }
        var command = operation.intent().operation().equals("dependencies_prepare")
                ? dependencies
                        .json()
                        .decode(operation.result().orElseThrow(), CodingResults.PreparationResult.class)
                        .command()
                : dependencies.json().decode(operation.result().orElseThrow(), CodingResults.CommandResult.class);
        return command.output().truncated();
    }
}
