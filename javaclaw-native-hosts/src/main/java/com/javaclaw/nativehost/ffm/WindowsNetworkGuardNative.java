package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Windows 网络守护的最小身份与 SCM FFM 边界；不导出任意服务管理或系统命令。 */
public final class WindowsNetworkGuardNative {
    private static final String SERVICE = "JavaClawNetworkGuard";

    private WindowsNetworkGuardNative() {}

    /**
     * 在五秒总截止时间内交换固定单行请求；连接必须以 OVERLAPPED 打开并已核验身份。
     *
     * @param pipe 由调用方独占且在本方法返回前不会关闭的 Pipe
     * @param request 已验证的 ASCII 单行协议内容
     * @return 去掉末尾换行的有界响应
     * @throws IOException I/O、取消或超时；异步内核访问退休后才返回
     */
    public static String exchange(MemorySegment pipe, String request) throws IOException {
        return WindowsGuardIo.exchange(pipe, request);
    }

    /**
     * 校验 Pipe 对端恰为 SCM 当前登记的守护进程，拒绝同名伪造 Pipe。
     *
     * @param pipe 已连接且由调用方拥有的本机 Pipe handle
     * @throws IOException 服务未运行或身份不匹配
     */
    public static void verifyServer(MemorySegment pipe) throws IOException {
        verifySystemPipeOwner(pipe);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pid = arena.allocate(JAVA_INT);
            check(Backend.call(Backend.GET_PIPE_SERVER_PID, pipe, pid), "GetNamedPipeServerProcessId");
            long actual = Integer.toUnsignedLong(pid.get(JAVA_INT, 0));
            if (actual == 0 || actual != serviceProcessId()) {
                throw new IOException("NETWORK_GUARD_SERVER_IDENTITY_MISMATCH");
            }
        }
    }

    private static void verifySystemPipeOwner(MemorySegment pipe) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment owner = arena.allocate(ADDRESS);
            MemorySegment descriptor = arena.allocate(ADDRESS);
            int result = Backend.call(
                    Backend.GET_HANDLE_SECURITY,
                    pipe,
                    1,
                    1,
                    owner,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    descriptor);
            if (result != 0) {
                throw new IOException("NETWORK_GUARD_PIPE_OWNER_UNAVAILABLE");
            }
            try {
                if (!"S-1-5-18".equals(sidText(owner.get(ADDRESS, 0)))) {
                    throw new IOException("NETWORK_GUARD_PIPE_OWNER_MISMATCH");
                }
            } finally {
                WindowsSandboxNative.localFree(descriptor.get(ADDRESS, 0));
            }
        }
    }

    /**
     * 查询固定服务是否已安装；停止或正在恢复的服务仍返回 true，避免重复请求系统授权。
     *
     * @return 仅当 SCM 明确报告服务不存在时返回 false
     * @throws IOException SCM 查询失败或访问被拒绝
     */
    public static boolean installed() throws IOException {
        MemorySegment manager =
                (MemorySegment) Backend.invoke(Backend.OPEN_SCM, MemorySegment.NULL, MemorySegment.NULL, 1);
        WindowsSandboxNative.requireHandle(manager, "OpenSCManagerW", 0);
        try (Arena arena = Arena.ofConfined()) {
            var result = WindowsSandboxNative.requireBackend()
                    .invoke(Backend.OPEN_SERVICE_CAPTURED, manager, WindowsSandboxNative.wide(arena, SERVICE), 4);
            if (result.address().address() != 0) {
                Backend.call(Backend.CLOSE_SERVICE, result.address());
                return true;
            }
            if (result.error() == 1060) {
                return false;
            }
            throw new IOException("NETWORK_GUARD_SERVICE_STATUS_UNAVAILABLE");
        } finally {
            Backend.call(Backend.CLOSE_SERVICE, manager);
        }
    }

    /**
     * 为首次准备依赖请求 Windows 系统授权，仅安装发行目录中的固定签名守护程序。
     *
     * @throws IOException 安装器缺失或系统拒绝启动 UAC 授权
     */
    public static void requestInstallation() throws IOException {
        Path image = Path.of(System.getProperty("java.home"))
                .toAbsolutePath()
                .normalize()
                .getParent()
                .resolve("app/native/JavaClawNetworkGuard.exe");
        if (!Files.isRegularFile(image)) {
            throw new IOException("NETWORK_GUARD_INSTALLER_MISSING");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) Backend.invoke(
                    Backend.SHELL_EXECUTE,
                    MemorySegment.NULL,
                    WindowsSandboxNative.wide(arena, "runas"),
                    WindowsSandboxNative.wide(arena, image.toString()),
                    WindowsSandboxNative.wide(arena, "--install"),
                    MemorySegment.NULL,
                    0);
            if (result.address() <= 32) {
                throw new IOException("NETWORK_GUARD_SYSTEM_AUTHORIZATION_REQUIRED");
            }
        }
    }

    /**
     * 将刚创建的控制目录设为当前所有者与 SYSTEM 专用，并切断父目录的继承授权。
     *
     * @param directory 尚未暴露给子进程的应用私有目录
     * @throws IOException 无法建立受保护 DACL；调用方不得继续启动目标
     */
    public static void protectControlDirectory(Path directory) throws IOException {
        try (WindowsFileHandle handle = WindowsFileHandle.openPath(
                        directory,
                        WindowsFileHandle.READ_CONTROL | WindowsFileHandle.WRITE_DAC | WindowsFileHandle.ATTRIBUTES,
                        WindowsFileHandle.DIRECTORY);
                Arena arena = Arena.ofConfined()) {
            String ownerSid = controlOwner(handle, arena);
            MemorySegment descriptor = arena.allocate(ADDRESS);
            check(
                    Backend.call(
                            Backend.PARSE_SECURITY,
                            WindowsSandboxNative.wide(arena, "D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;" + ownerSid + ")"),
                            1,
                            descriptor,
                            MemorySegment.NULL),
                    "ConvertStringSecurityDescriptorToSecurityDescriptorW");
            try {
                controlDacl(handle, descriptor.get(ADDRESS, 0), arena);
            } finally {
                WindowsSandboxNative.localFree(descriptor.get(ADDRESS, 0));
            }
        }
    }

    private static String controlOwner(WindowsFileHandle handle, Arena arena) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment owner = arena.allocate(ADDRESS);
        MemorySegment descriptor = arena.allocate(ADDRESS);
        var result = backend.invoke(
                backend.getSecurityInfo,
                handle.address(),
                1,
                1,
                owner,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
                descriptor);
        if (result.number() != 0) {
            throw new IOException("NETWORK_GUARD_CONTROL_OWNER_UNAVAILABLE");
        }
        try {
            return sidText(owner.get(ADDRESS, 0));
        } finally {
            WindowsSandboxNative.localFree(descriptor.get(ADDRESS, 0));
        }
    }

    private static void controlDacl(WindowsFileHandle handle, MemorySegment descriptor, Arena arena)
            throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment present = arena.allocate(JAVA_INT);
        MemorySegment dacl = arena.allocate(ADDRESS);
        MemorySegment defaulted = arena.allocate(JAVA_INT);
        var extracted = backend.invoke(backend.getSecurityDescriptorDacl, descriptor, present, dacl, defaulted);
        if (extracted.number() == 0 || present.get(JAVA_INT, 0) == 0) {
            throw new IOException("NETWORK_GUARD_CONTROL_DACL_UNAVAILABLE");
        }
        var applied = backend.invoke(
                backend.setSecurityInfo,
                handle.address(),
                1,
                0x80000004,
                MemorySegment.NULL,
                MemorySegment.NULL,
                dacl.get(ADDRESS, 0),
                MemorySegment.NULL);
        if (applied.number() != 0) {
            throw WindowsSandboxNative.status("protect control directory", applied.number());
        }
    }

    static long processId(MemorySegment process) throws IOException {
        long result = Integer.toUnsignedLong(Backend.call(Backend.GET_PROCESS_ID, process));
        if (result == 0) {
            throw new IOException("NETWORK_GUARD_PROCESS_ID_UNAVAILABLE");
        }
        return result;
    }

    static String sidText(MemorySegment sid) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(ADDRESS);
            check(Backend.call(Backend.SID_TO_STRING, sid, output), "ConvertSidToStringSidW");
            MemorySegment value = output.get(ADDRESS, 0);
            try {
                return WindowsSandboxNative.readWide(value);
            } finally {
                WindowsSandboxNative.localFree(value);
            }
        }
    }

    private static long serviceProcessId() throws IOException {
        MemorySegment manager =
                (MemorySegment) Backend.invoke(Backend.OPEN_SCM, MemorySegment.NULL, MemorySegment.NULL, 1);
        WindowsSandboxNative.requireHandle(manager, "OpenSCManagerW", 0);
        MemorySegment service = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            service = (MemorySegment)
                    Backend.invoke(Backend.OPEN_SERVICE, manager, WindowsSandboxNative.wide(arena, SERVICE), 4);
            WindowsSandboxNative.requireHandle(service, "OpenServiceW", 0);
            MemorySegment status = arena.allocate(36, 4);
            MemorySegment size = arena.allocate(JAVA_INT);
            check(Backend.call(Backend.QUERY_SERVICE, service, 0, status, 36, size), "QueryServiceStatusEx");
            if (status.get(JAVA_INT, 4) != 4) {
                throw new IOException("NETWORK_GUARD_SERVICE_NOT_RUNNING");
            }
            return Integer.toUnsignedLong(status.get(JAVA_INT, 28));
        } finally {
            if (service.address() != 0) {
                Backend.call(Backend.CLOSE_SERVICE, service);
            }
            Backend.call(Backend.CLOSE_SERVICE, manager);
        }
    }

    private static void check(int result, String operation) throws IOException {
        if (result == 0) {
            throw new IOException(operation + " failed for network guard");
        }
    }

    private static final class Backend {
        private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
        private static final SymbolLookup ADVAPI = SymbolLookup.libraryLookup("advapi32.dll", Arena.global());
        private static final SymbolLookup SHELL = SymbolLookup.libraryLookup("shell32.dll", Arena.global());
        private static final MethodHandle GET_PIPE_SERVER_PID =
                bind(KERNEL, "GetNamedPipeServerProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle GET_PROCESS_ID =
                bind(KERNEL, "GetProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle SID_TO_STRING =
                bind(ADVAPI, "ConvertSidToStringSidW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle OPEN_SCM =
                bind(ADVAPI, "OpenSCManagerW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        private static final MethodHandle OPEN_SERVICE =
                bind(ADVAPI, "OpenServiceW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        private static final MethodHandle OPEN_SERVICE_CAPTURED = Linker.nativeLinker()
                .downcallHandle(
                        ADVAPI.find("OpenServiceW").orElseThrow(),
                        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
                        Linker.Option.captureCallState("GetLastError"));
        private static final MethodHandle QUERY_SERVICE = bind(
                ADVAPI,
                "QueryServiceStatusEx",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        private static final MethodHandle CLOSE_SERVICE =
                bind(ADVAPI, "CloseServiceHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle SHELL_EXECUTE = bind(
                SHELL,
                "ShellExecuteW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        private static final MethodHandle PARSE_SECURITY = bind(
                ADVAPI,
                "ConvertStringSecurityDescriptorToSecurityDescriptorW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle GET_HANDLE_SECURITY = bind(
                ADVAPI,
                "GetSecurityInfo",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

        private static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker().downcallHandle(library.find(name).orElseThrow(), descriptor);
        }

        private static int call(MethodHandle handle, Object... arguments) throws IOException {
            return (int) invoke(handle, arguments);
        }

        private static Object invoke(MethodHandle handle, Object... arguments) throws IOException {
            try {
                return handle.invokeWithArguments(arguments);
            } catch (Throwable failure) {
                throw new IOException("Windows network guard native call failed", failure);
            }
        }
    }
}
