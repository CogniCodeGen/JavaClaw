package com.javaclaw.server.coding;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.server.persistence.CodingEnvironmentRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.CommandIdentity;

/** 管理请求仅提供配置、安装和持久证据，项目命令没有管理路由。 */
final class CodingManagement {
    private final CodingPlatform.Dependencies dependencies;
    private final CodingEnvironmentRepository environments;
    private final CodingOperationRepository operations;
    private final CodingTerminalManager terminals;
    private final CodingCommandQueries commandQueries;

    CodingManagement(
            CodingPlatform.Dependencies dependencies,
            CodingEnvironmentRepository environments,
            CodingOperationRepository operations,
            CodingTerminalManager terminals) {
        this.dependencies = dependencies;
        this.environments = environments;
        this.operations = operations;
        this.terminals = terminals;
        commandQueries = new CodingCommandQueries(dependencies);
    }

    ExtensionResponse invoke(ExtensionRequest request, ContributionKind kind, CancellationToken cancellation)
            throws Exception {
        cancellation.throwIfCancelled();
        dependencies
                .core()
                .findWorkspace(request.workspaceId())
                .orElseThrow(() -> new SecurityException("Coding Workspace 不存在"));
        if (kind == ContributionKind.COMMAND) {
            return command(request, cancellation);
        }
        if (kind != ContributionKind.QUERY) {
            throw new SecurityException("管理请求不能启动 Coding 工具");
        }
        return query(request);
    }

    private ExtensionResponse command(ExtensionRequest request, CancellationToken cancellation) throws Exception {
        if (request.operation().equals("toolchain/install")) {
            return response(dependencies.toolchains().install(request, cancellation), 0);
        }
        if (!request.operation().equals("environment/update")) {
            throw new SecurityException("Coding 未声明此管理命令");
        }
        var update = dependencies.json().decode(request.payload(), CodingEnvironmentContracts.EnvironmentUpdate.class);
        var catalog = dependencies.toolchains().catalog();
        for (var reference : update.spec().toolchains()) {
            if (catalog.artifacts().stream()
                    .noneMatch(artifact -> artifact.reference().equals(reference))) {
                throw new SecurityException("Coding 环境引用未知或跨平台的工具链");
            }
        }
        var identity = new CommandIdentity(
                "coding/environment-update",
                request.idempotencyKey().orElseThrow(),
                request.expectedRevision(),
                dependencies.json().encode(request).sha256());
        var environment = environments.update(request.workspaceId(), identity, update.spec());
        return response(environment, environment.revision());
    }

    private ExtensionResponse query(ExtensionRequest request) {
        return switch (request.operation()) {
            case "toolchain/catalog" -> response(dependencies.toolchains().catalog(), 0);
            case "toolchain/list" -> response(dependencies.toolchains().installed(request.workspaceId()), 0);
            case "environment/read" -> environment(request);
            case "preparation/read" -> preparation(request);
            case "preparation/output", "command/output" -> output(request);
            case "execution/list" -> response(commandQueries.list(request), 0);
            case "preparation/evidence" -> evidence(request);
            case "change/list", "diff/read" -> changes(request);
            case "terminal/read", "terminal/output" -> terminal(request);
            default -> throw new SecurityException("Coding 未声明此查询");
        };
    }

    private ExtensionResponse environment(ExtensionRequest request) {
        var environment = environments.read(request.workspaceId());
        return response(environment, environment.revision());
    }

    private ExtensionResponse changes(ExtensionRequest request) {
        var input = dependencies.json().decode(request.payload(), CodingResults.ResourceRead.class);
        var operation = require(request, input.resourceId(), "file_apply_patch");
        return response(
                dependencies.json().decode(operation.result().orElseThrow(), CodingResults.PatchResult.class), 0);
    }

    private ExtensionResponse preparation(ExtensionRequest request) {
        var input = dependencies.json().decode(request.payload(), CodingResults.ResourceRead.class);
        var operation = require(request, input.resourceId(), "dependencies_prepare");
        return response(
                dependencies.json().decode(operation.result().orElseThrow(), CodingResults.PreparationResult.class), 0);
    }

    private ExtensionResponse evidence(ExtensionRequest request) {
        var input = dependencies.json().decode(request.payload(), CodingResults.ResourceRead.class);
        require(request, input.resourceId(), "dependencies_prepare");
        return response(
                new com.javaclaw.server.persistence.DependencyEvidenceRepository(
                                dependencies.database(), dependencies.json())
                        .read(request.workspaceId(), input.resourceId()),
                0);
    }

    private ExtensionResponse output(ExtensionRequest request) {
        var input = dependencies.json().decode(request.payload(), CodingResults.OutputRead.class);
        String name = request.operation().equals("command/output") ? "command_run" : "dependencies_prepare";
        var operation = require(request, input.resourceId(), name);
        return response(commandQueries.output(request, operation), 0);
    }

    private ExtensionResponse terminal(ExtensionRequest request) {
        String id;
        long offset = 0;
        int maximum = 1;
        if (request.operation().equals("terminal/output")) {
            var input = dependencies.json().decode(request.payload(), CodingResults.OutputRead.class);
            id = input.resourceId();
            offset = input.offsetBytes();
            maximum = input.maxBytes();
        } else {
            id = dependencies
                    .json()
                    .decode(request.payload(), CodingResults.ResourceRead.class)
                    .resourceId();
        }
        require(request, id, "terminal_open");
        var result = request.operation().equals("terminal/output")
                ? terminals.output(request.workspaceId(), id, offset, maximum)
                : terminals.snapshot(request.workspaceId(), id, offset, maximum);
        return response(result, 0);
    }

    private CodingOperationRepository.Operation require(ExtensionRequest request, String id, String operationName) {
        var operation =
                operations.find(request.workspaceId(), id).orElseThrow(() -> new SecurityException("Coding 记录不存在"));
        if (!operation.intent().operation().equals(operationName)) {
            throw new SecurityException("Coding 记录种类不匹配");
        }
        dependencies.authority().requireEvidence(operation.intent().turnId(), request.workspaceId());
        request.threadId().ifPresent(thread -> {
            var turn = dependencies.core().findTurn(operation.intent().turnId()).orElseThrow();
            if (!turn.threadId().equals(thread)) {
                throw new SecurityException("Coding 记录不属于当前 Thread");
            }
        });
        request.turnId().ifPresent(turn -> {
            if (!turn.equals(operation.intent().turnId())) {
                throw new SecurityException("Coding 记录不属于当前 Turn");
            }
        });
        return operation;
    }

    private ExtensionResponse response(Object value, long revision) {
        return new ExtensionResponse(dependencies.json().encode(value), revision);
    }
}
