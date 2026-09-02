package com.javaclaw.server.extension.thirdparty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.protocol.CanonicalJson;

/** 以单次无 shell Sandbox 进程执行第三方 Worker 协议。 */
final class ThirdPartyWorkerClient {
    private static final int WORKER_PROTOCOL_VERSION = 1;
    private static final int MAX_ACTION_ROUNDS = 4;
    private final SandboxExecutor sandbox;
    private final CanonicalJson json;
    private final ThirdPartyWorkerActionHandler actions;

    ThirdPartyWorkerClient(SandboxExecutor sandbox, CanonicalJson json, ThirdPartyWorkerActionHandler actions) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.json = Objects.requireNonNull(json, "json");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    void health(InstalledThirdPartyBundle bundle) throws Exception {
        PermissionProfile processPermission = ThirdPartyPermissionPolicy.processPermission(
                bundle.manifest(), bundle.root(), Optional.empty(), Optional.empty(), json);
        WorkerRequest request = new WorkerRequest(
                WORKER_PROTOCOL_VERSION,
                InvocationKind.HEALTH,
                bundle.descriptor().id().value(),
                bundle.descriptor().revision(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "health",
                new CanonicalPayload("{}"),
                Optional.empty(),
                0,
                0,
                List.of());
        invoke(bundle, request, processPermission, processPermission, new CancellationSource(), false);
    }

    ExtensionResponse invoke(InstalledThirdPartyBundle bundle, ThirdPartyInvocation invocation) throws Exception {
        PermissionProfile processPermission = ThirdPartyPermissionPolicy.processPermission(
                bundle.manifest(),
                bundle.root(),
                Optional.of(invocation.workspace().root()),
                invocation.caller(),
                json);
        PermissionProfile brokerPermission =
                ThirdPartyPermissionPolicy.brokerPermission(bundle.manifest(), invocation.caller());
        WorkerRequest request = new WorkerRequest(
                WORKER_PROTOCOL_VERSION,
                invocation.kind(),
                bundle.descriptor().id().value(),
                bundle.descriptor().revision(),
                Optional.of(invocation.workspace().id()),
                invocation.threadId(),
                invocation.turnId(),
                invocation.operation(),
                invocation.payload(),
                invocation.idempotencyKey(),
                invocation.expectedRevision(),
                0,
                List.of());
        return invoke(bundle, request, processPermission, brokerPermission, invocation.cancellation(), true);
    }

    private ExtensionResponse invoke(
            InstalledThirdPartyBundle bundle,
            WorkerRequest request,
            PermissionProfile processPermission,
            PermissionProfile brokerPermission,
            CancellationToken cancellation,
            boolean allowActions)
            throws Exception {
        WorkerRequest current = request;
        for (int round = 0; round <= MAX_ACTION_ROUNDS; round++) {
            WorkerResponse response = invokeOnce(bundle, current, processPermission, cancellation);
            if (response.actions().isEmpty()) {
                return finalResponse(response);
            }
            if (!allowActions) {
                throw new SecurityException("health checks cannot request Host actions");
            }
            if (round == MAX_ACTION_ROUNDS) {
                throw new IllegalStateException("extension worker exceeded the Host action round limit");
            }
            current = current.nextRound(
                    round + 1, actions.execute(bundle, brokerPermission, cancellation, response.actions()));
        }
        throw new IllegalStateException("extension worker action loop did not terminate");
    }

    private WorkerResponse invokeOnce(
            InstalledThirdPartyBundle bundle,
            WorkerRequest request,
            PermissionProfile permission,
            CancellationToken cancellation)
            throws Exception {
        cancellation.throwIfCancelled();
        List<String> arguments = new ArrayList<>();
        Path executable = bundle.root()
                .resolve(bundle.manifest().entryPoint().executable())
                .normalize();
        if (!executable.startsWith(bundle.root())) {
            throw new SecurityException("extension entryPoint escapes install root");
        }
        arguments.add(executable.toString());
        arguments.addAll(bundle.manifest().entryPoint().arguments());
        Duration timeout = bundle.manifest().permissions().maxRunTime();
        SandboxCommand command = new SandboxCommand(
                "extension-" + bundle.descriptor().id().value(),
                arguments,
                bundle.root(),
                Map.of("JAVACLAW_EXTENSION_ID", bundle.descriptor().id().value()),
                json.encode(request).json().getBytes(StandardCharsets.UTF_8),
                SandboxMode.BATCH,
                timeout);
        SandboxResult result = sandbox.execute(command, permission, cancellation);
        if (result.cancelled()) {
            cancellation.throwIfCancelled();
            throw new IllegalStateException("extension worker was cancelled");
        }
        if (result.timedOut()) {
            throw new IllegalStateException("extension worker timed out");
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException("extension worker exited with code " + result.exitCode());
        }
        String output = new String(result.standardOutput(), StandardCharsets.UTF_8).strip();
        if (output.isEmpty()) {
            throw new IllegalStateException("extension worker returned no response");
        }
        return json.decode(json.parse(output), WorkerResponse.class);
    }

    private static ExtensionResponse finalResponse(WorkerResponse response) throws ExtensionWorkerException {
        if (!response.ok()) {
            WorkerError error = response.error()
                    .orElseThrow(() -> new IllegalStateException("extension worker returned an invalid error"));
            throw new ExtensionWorkerException(error.code(), error.message());
        }
        if (response.error().isPresent()) {
            throw new IllegalStateException("successful extension response must not contain an error");
        }
        return new ExtensionResponse(response.payload(), response.revision());
    }

    enum InvocationKind {
        HEALTH,
        QUERY,
        COMMAND,
        TOOL
    }

    private record WorkerRequest(
            int protocolVersion,
            InvocationKind kind,
            String extensionId,
            long extensionRevision,
            Optional<WorkspaceId> workspaceId,
            Optional<ThreadId> threadId,
            Optional<TurnId> turnId,
            String operation,
            CanonicalPayload payload,
            Optional<String> idempotencyKey,
            long expectedRevision,
            int round,
            List<ThirdPartyWorkerProtocol.ActionResult> actionResults) {
        private WorkerRequest {
            actionResults = ThirdPartyWorkerProtocol.immutable(actionResults, "actionResults");
            if (round < 0) {
                throw new IllegalArgumentException("worker round must not be negative");
            }
        }

        private WorkerRequest nextRound(int nextRound, List<ThirdPartyWorkerProtocol.ActionResult> results) {
            return new WorkerRequest(
                    protocolVersion,
                    kind,
                    extensionId,
                    extensionRevision,
                    workspaceId,
                    threadId,
                    turnId,
                    operation,
                    payload,
                    idempotencyKey,
                    expectedRevision,
                    nextRound,
                    results);
        }
    }

    private record WorkerResponse(
            boolean ok,
            CanonicalPayload payload,
            long revision,
            Optional<WorkerError> error,
            List<ThirdPartyWorkerProtocol.Action> actions) {
        private WorkerResponse {
            Objects.requireNonNull(payload, "payload");
            error = Objects.requireNonNull(error, "error");
            actions = ThirdPartyWorkerProtocol.immutable(actions, "actions");
            if (revision < 0) {
                throw new IllegalArgumentException("worker revision must not be negative");
            }
            if (!actions.isEmpty() && (!ok || error.isPresent())) {
                throw new IllegalArgumentException("worker action continuation must be successful");
            }
        }
    }

    private record WorkerError(String code, String message) {
        private WorkerError {
            if (code == null || code.isBlank() || message == null || message.isBlank()) {
                throw new IllegalArgumentException("worker error must contain code and message");
            }
        }
    }

    static final class ExtensionWorkerException extends Exception {
        private final String code;

        ExtensionWorkerException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
