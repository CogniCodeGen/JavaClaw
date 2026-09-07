package com.javaclaw.server.coding;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess;

/** 进程生命周期所需的原生边界；生产实例仅委托真实平台 Sandbox，测试可控制退出和清理时序。 */
interface CodingProcessSandbox {
    SandboxResult execute(
            SandboxCommand command,
            PermissionProfile permission,
            CancellationToken cancellation,
            SandboxRuntimeAccess access,
            SandboxNetworkAccess network)
            throws Exception;

    /**
     * 增量输出入口；生产重载直接观察真实管道，旧测试替身在返回结果后按原始通道补发。
     *
     * <p>观察器失败必须使执行失败，不能把持久化失败伪装成完整输出。
     */
    default SandboxResult execute(
            SandboxCommand command,
            PermissionProfile permission,
            CancellationToken cancellation,
            SandboxRuntimeAccess access,
            SandboxNetworkAccess network,
            java.util.function.Consumer<SandboxFrame> observer)
            throws Exception {
        SandboxResult result = execute(command, permission, cancellation, access, network);
        if (result.standardOutput().length > 0) {
            observer.accept(new SandboxFrame("stdout", result.standardOutput(), java.time.Instant.now()));
        }
        if (result.standardError().length > 0) {
            observer.accept(new SandboxFrame("stderr", result.standardError(), java.time.Instant.now()));
        }
        return result;
    }

    SandboxSession open(
            SandboxCommand command,
            PermissionProfile permission,
            CancellationToken cancellation,
            SandboxRuntimeAccess access,
            SandboxNetworkAccess network)
            throws Exception;

    static CodingProcessSandbox using(PlatformSandboxExecutor sandbox) {
        return new CodingProcessSandbox() {
            @Override
            public SandboxResult execute(
                    SandboxCommand command,
                    PermissionProfile permission,
                    CancellationToken cancellation,
                    SandboxRuntimeAccess access,
                    SandboxNetworkAccess network)
                    throws Exception {
                return sandbox.execute(command, permission, cancellation, access, network);
            }

            @Override
            public SandboxResult execute(
                    SandboxCommand command,
                    PermissionProfile permission,
                    CancellationToken cancellation,
                    SandboxRuntimeAccess access,
                    SandboxNetworkAccess network,
                    java.util.function.Consumer<SandboxFrame> observer)
                    throws Exception {
                return sandbox.execute(command, permission, cancellation, access, network, observer);
            }

            @Override
            public SandboxSession open(
                    SandboxCommand command,
                    PermissionProfile permission,
                    CancellationToken cancellation,
                    SandboxRuntimeAccess access,
                    SandboxNetworkAccess network)
                    throws Exception {
                return sandbox.open(command, permission, cancellation, access, network);
            }
        };
    }
}
