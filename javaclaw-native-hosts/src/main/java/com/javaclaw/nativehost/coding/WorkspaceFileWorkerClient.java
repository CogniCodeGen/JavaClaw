package com.javaclaw.nativehost.coding;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.nativehost.sandbox.SandboxJavaRuntime;
import com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess;

/** 固定文件 Worker 的一次性有界调用；不持有协议返回流之外的进程资源。 */
final class WorkspaceFileWorkerClient {
    private WorkspaceFileWorkerClient() {}

    static DataInputStream invoke(
            Path root,
            PermissionProfile permission,
            SandboxJavaRuntime runtime,
            String operation,
            byte[] request,
            CancellationToken cancellation)
            throws Exception {
        Path workingDirectory = Stream.concat(
                        permission.files().readRoots().stream(), permission.files().writeRoots().stream())
                .findFirst()
                .orElseThrow(() -> new SecurityException("Workspace file operation has no effective file roots"));
        SandboxCommand command = new SandboxCommand(
                "workspace-file-" + operation,
                runtime.command(WorkspaceFileWorker.class, List.of(root.toString()), 128),
                workingDirectory,
                Map.of(),
                request,
                SandboxMode.BATCH,
                java.time.Duration.ofSeconds(30)
                                        .compareTo(permission.processes().maxRunTime())
                                > 0
                        ? permission.processes().maxRunTime()
                        : java.time.Duration.ofSeconds(30));
        SandboxResult result = new PlatformSandboxExecutor()
                .execute(
                        command,
                        permission,
                        cancellation,
                        new SandboxRuntimeAccess(runtime.readRoots(), List.of(), List.of(runtime.executable())),
                        SandboxNetworkAccess.offline());
        if (result.cancelled() || result.timedOut() || result.exitCode() != 0) {
            String diagnostic = new String(result.standardError(), StandardCharsets.UTF_8);
            throw new IOException("Workspace file Worker did not complete: exit=" + result.exitCode() + "; "
                    + diagnostic.substring(0, Math.min(4096, diagnostic.length())));
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(result.standardOutput()));
        if (!input.readBoolean()) {
            String detail = input.readUTF();
            input.close();
            throw new IOException(detail);
        }
        return input;
    }
}
