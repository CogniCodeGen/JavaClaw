package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Windows 64 位 AppContainer、Restricted Token、Job Object 与 ConPTY 的组合入口。 */
public final class WindowsSandbox {
    private WindowsSandbox() {}

    /**
     * 检查当前进程是否能加载全部必需的 Windows API。
     *
     * @return 能尝试启动 v6 Windows Sandbox 时为 true
     */
    public static boolean isSupported() {
        return WindowsSandboxNative.isAvailable();
    }

    /**
     * 在临时 AppContainer 中执行批处理命令，并在返回前恢复文件 ACL。调用线程必须绑定可信 WindowsAclEvidence。
     *
     * @param request 已验证请求
     * @return 目标进程退出码；备用内部超时终止时为 124
     * @throws IOException 原生启动、等待或权限恢复失败
     */
    public static int run(WindowsSandboxRequest request) throws IOException {
        WindowsSandboxPaths.Prepared prepared = WindowsSandboxPaths.prepare(Objects.requireNonNull(request, "request"));
        try (WindowsAppContainerScope scope = WindowsAppContainerScope.open(prepared)) {
            return WindowsProcessLauncher.run(prepared, scope);
        }
    }

    /**
     * 打开由 AppContainer 与 Job Object 共同约束的 ConPTY 会话。调用线程必须绑定可信 WindowsAclEvidence。
     *
     * @param request 已验证请求
     * @param columns 初始列数，20 到 1000
     * @param rows 初始行数，5 到 1000
     * @return 调用方拥有的会话
     * @throws IOException 原生启动或临时授权失败
     */
    public static PseudoConsoleSession openPseudoConsole(WindowsSandboxRequest request, int columns, int rows)
            throws IOException {
        WindowsSandboxPaths.Prepared prepared = WindowsSandboxPaths.prepare(Objects.requireNonNull(request, "request"));
        WindowsAppContainerScope scope = WindowsAppContainerScope.open(prepared);
        try {
            return new PseudoConsoleSession(WindowsProcessLauncher.openPseudoConsole(prepared, scope, columns, rows));
        } catch (IOException | RuntimeException failure) {
            try {
                scope.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** 调用方拥有的 Windows ConPTY；关闭时终止整个 Job 并恢复临时 ACL。 */
    public static final class PseudoConsoleSession implements AutoCloseable {
        private final WindowsPseudoConsole delegate;

        private PseudoConsoleSession(WindowsPseudoConsole delegate) {
            this.delegate = delegate;
        }

        /**
         * 阻塞读取至多指定字节；ConPTY 输出结束时返回 null。
         *
         * @param maximumBytes 单次读取上限，1 到 1 MiB
         * @return 输出字节或 null
         * @throws IOException 原生读取失败
         */
        public byte[] read(int maximumBytes) throws IOException {
            return delegate.read(maximumBytes);
        }

        /**
         * 串行写完输入字节。
         *
         * @param bytes 最多 1 MiB 的输入；实现会复制
         * @throws IOException 输入已关闭或写入失败
         */
        public void write(byte[] bytes) throws IOException {
            delegate.write(bytes);
        }

        /** 幂等关闭输入端，不影响已经缓冲的输出。 */
        public void closeInput() {
            delegate.closeInput();
        }

        /**
         * 调整终端字符尺寸。
         *
         * @param columns 列数，20 到 1000
         * @param rows 行数，5 到 1000
         * @throws IOException 会话已关闭或原生调整失败
         */
        public void resize(int columns, int rows) throws IOException {
            delegate.resize(columns, rows);
        }

        /**
         * 写入 ETX，让 ConPTY 生成 Ctrl+C 输入。
         *
         * @throws IOException 输入已关闭或写入失败
         */
        public void interrupt() throws IOException {
            delegate.interrupt();
        }

        /**
         * 终止 Job 中的完整进程树。
         *
         * @param exitCode 进程退出码
         */
        public void terminate(int exitCode) {
            delegate.terminate(exitCode);
        }

        /**
         * 查询目标进程是否仍在运行。
         *
         * @return 仍运行时为 true
         * @throws IOException 状态读取失败
         */
        public boolean isAlive() throws IOException {
            return delegate.isAlive();
        }

        /**
         * 有界等待目标退出。
         *
         * @param timeout 等待时间，最长 24 小时
         * @return 退出码；时间到但仍运行时为 null
         * @throws IOException 原生等待或退出码读取失败
         */
        public Integer awaitExit(Duration timeout) throws IOException {
            return delegate.awaitExit(timeout);
        }

        /** 关闭 ConPTY 服务端，使读取者排空缓冲后观察到 EOF。 */
        public void finishOutput() {
            delegate.finishOutput();
        }

        /** 终止残留进程、关闭全部句柄并恢复临时 ACL。 */
        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /** 临时 ACL 或 AppContainer profile 未能恢复；上层必须锁定对应 Workspace。 */
    public static final class AclRestorationException extends IOException {
        private final Optional<Path> evidenceDirectory;
        /**
         * 保存不含凭据的诊断与清理根因。
         *
         * @param message 清理阶段说明
         * @param cause 原生恢复失败
         */
        public AclRestorationException(String message, Throwable cause) {
            this(message, cause, Optional.empty());
        }

        /**
         * 绑定平台私有目录中的恢复凭据；路径只来自可信 helper 通道，不能由目标输出指定。
         *
         * @param message 清理说明
         * @param cause 原始失败
         * @param evidenceDirectory 原始 ACL 及对象身份凭据目录；无法确认时为空
         */
        public AclRestorationException(String message, Throwable cause, Optional<Path> evidenceDirectory) {
            super(message, cause);
            this.evidenceDirectory = Objects.requireNonNull(evidenceDirectory, "evidenceDirectory")
                    .map(path -> path.toAbsolutePath().normalize());
        }

        /** @return 可信恢复凭据目录；为空时不能自动解除隔离锁 */
        public Optional<Path> evidenceDirectory() {
            return evidenceDirectory;
        }
    }
}
