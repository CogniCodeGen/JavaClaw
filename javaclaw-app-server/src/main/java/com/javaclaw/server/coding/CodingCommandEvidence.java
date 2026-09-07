package com.javaclaw.server.coding;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CodingOperationRepository;

/** 启动前保存真实 argv 与项目启动配置摘要；已有依赖准备计划作为原始规范载荷保留。 */
record CodingCommandEvidence(
        List<String> argv,
        String relativeCwd,
        long timeoutMillis,
        String networkMode,
        Optional<MavenProjectLaunch.Evidence> maven,
        Optional<CanonicalPayload> precedingPreparation) {

    static void store(
            CodingOperationRepository operations,
            CanonicalJson json,
            CodingInvocation invocation,
            ManagedCommandResolver.Resolved resolved,
            SandboxCommand command,
            String networkMode) {
        var operation =
                operations.find(invocation.workspaceId(), invocation.id()).orElseThrow();
        String relative = invocation
                .turn()
                .executionRoot()
                .relativize(command.workingDirectory())
                .toString()
                .replace('\\', '/');
        var evidence = new CodingCommandEvidence(
                command.argv(),
                relative.isEmpty() ? "." : relative,
                command.timeout().toMillis(),
                networkMode,
                resolved.maven(),
                operation.preparation());
        // 此提交只证明启动意图；后续外部副作用与最终 ToolResult 的事务不在此处伪装成原子操作。
        operations.preparation(invocation.id(), json.encode(evidence));
    }
}
