package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** Windows Sandbox 使用的惰性 Win32 FFM 绑定与错误捕获。 */
final class WindowsSandboxNative {
    static final StructLayout TOKEN_MANDATORY_LABEL = MemoryLayout.structLayout(
            ADDRESS.withName("sid"), JAVA_INT.withName("attributes"), MemoryLayout.paddingLayout(4));
    static final StructLayout SECURITY_CAPABILITIES = MemoryLayout.structLayout(
            ADDRESS.withName("appContainerSid"),
            ADDRESS.withName("capabilities"),
            JAVA_INT.withName("capabilityCount"),
            JAVA_INT.withName("reserved"));
    static final StructLayout COORD = MemoryLayout.structLayout(JAVA_SHORT.withName("x"), JAVA_SHORT.withName("y"));
    static final StructLayout EXPLICIT_ACCESS = MemoryLayout.structLayout(
            JAVA_INT.withName("permissions"),
            JAVA_INT.withName("mode"),
            JAVA_INT.withName("inheritance"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("multipleTrustee"),
            JAVA_INT.withName("multipleOperation"),
            JAVA_INT.withName("trusteeForm"),
            JAVA_INT.withName("trusteeType"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("trusteeName"));
    static final StructLayout STARTUP_INFO_EX = MemoryLayout.structLayout(
            JAVA_INT.withName("cb"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("reserved"),
            ADDRESS.withName("desktop"),
            ADDRESS.withName("title"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("xSize"),
            JAVA_INT.withName("ySize"),
            JAVA_INT.withName("xChars"),
            JAVA_INT.withName("yChars"),
            JAVA_INT.withName("fill"),
            JAVA_INT.withName("flags"),
            JAVA_SHORT.withName("show"),
            JAVA_SHORT.withName("reservedBytes"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("reserved2"),
            ADDRESS.withName("stdin"),
            ADDRESS.withName("stdout"),
            ADDRESS.withName("stderr"),
            ADDRESS.withName("attributes"));

    private WindowsSandboxNative() {}

    static boolean isAvailable() {
        if (!isWindows() || ADDRESS.byteSize() != 8) {
            return false;
        }
        try {
            Backend.instance();
            return true;
        } catch (RuntimeException | ExceptionInInitializerError unavailable) {
            return false;
        }
    }

    static Backend requireBackend() {
        if (!isWindows() || ADDRESS.byteSize() != 8) {
            throw new UnsupportedOperationException("Windows Sandbox requires 64-bit Windows");
        }
        try {
            return Backend.instance();
        } catch (RuntimeException | ExceptionInInitializerError failure) {
            throw new UnsupportedOperationException("required Windows Sandbox APIs are unavailable", failure);
        }
    }

    static MemorySegment wide(Arena arena, String value) {
        return arena.allocateFrom(value + '\0', StandardCharsets.UTF_16LE);
    }

    static String readWide(MemorySegment address) throws IOException {
        if (nullHandle(address)) {
            return "";
        }
        try {
            return address.reinterpret(1024 * 1024).getString(0, StandardCharsets.UTF_16LE);
        } catch (IndexOutOfBoundsException failure) {
            throw new IOException("Windows native string is unterminated", failure);
        }
    }

    static MemorySegment global(MemorySegment handle) {
        return nullHandle(handle) ? MemorySegment.NULL : MemorySegment.ofAddress(handle.address());
    }

    static boolean nullHandle(MemorySegment handle) {
        return handle == null || handle.address() == 0;
    }

    static void requireHandle(MemorySegment handle, String operation, int error) throws IOException {
        if (nullHandle(handle) || handle.address() == -1L) {
            throw error(operation, error);
        }
    }

    static IOException error(String operation, int code) {
        return new IOException(operation + " failed (Windows error " + Integer.toUnsignedString(code) + ")");
    }

    static IOException status(String operation, int code) {
        return new IOException(operation + " failed (Windows status " + Integer.toUnsignedString(code) + ")");
    }

    static void closeHandle(MemorySegment handle) throws IOException {
        if (nullHandle(handle)) {
            return;
        }
        CallResult result = requireBackend().invoke(requireBackend().closeHandle, handle);
        if (result.number() == 0) {
            throw error("CloseHandle", result.error());
        }
    }

    static void closeHandleQuietly(MemorySegment handle) {
        try {
            closeHandle(handle);
        } catch (IOException ignored) {
        }
    }

    static void closePseudoConsole(MemorySegment handle) {
        if (!nullHandle(handle)) {
            requireBackend().invokePlainQuietly(requireBackend().closePseudoConsole, handle);
        }
    }

    static void cancelIoQuietly(MemorySegment handle) {
        if (!nullHandle(handle)) {
            try {
                Backend backend = requireBackend();
                backend.invoke(backend.cancelIoEx, handle, MemorySegment.NULL);
            } catch (IOException ignored) {
                // 关闭路径只需唤醒可能阻塞的同步 I/O；随后 CloseHandle 仍会释放句柄。
            }
        }
    }

    static void terminateProcessQuietly(MemorySegment process, int exitCode) {
        if (!nullHandle(process)) {
            try {
                Backend backend = requireBackend();
                backend.invoke(backend.terminateProcess, process, exitCode);
            } catch (IOException ignored) {
                // Job Object 或进程关闭可能已完成同一终止动作。
            }
        }
    }

    static void deleteAttributeList(MemorySegment list) {
        if (!nullHandle(list)) {
            requireBackend().invokePlainQuietly(requireBackend().deleteAttributeList, list);
        }
    }

    static void localFree(MemorySegment value) {
        if (!nullHandle(value)) {
            requireBackend().invokePlainQuietly(requireBackend().localFree, value);
        }
    }

    static void freeSid(MemorySegment value) {
        if (!nullHandle(value)) {
            requireBackend().invokePlainQuietly(requireBackend().freeSid, value);
        }
    }

    static MemorySegment coord(Arena arena, int columns, int rows) {
        if (columns < 20 || columns > 1_000 || rows < 5 || rows > 1_000) {
            throw new IllegalArgumentException("ConPTY dimensions are invalid");
        }
        MemorySegment value = arena.allocate(COORD);
        value.set(JAVA_SHORT, 0, (short) columns);
        value.set(JAVA_SHORT, 2, (short) rows);
        return value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    record CallResult(Object value, int error) {
        int number() {
            return (int) value;
        }

        MemorySegment address() {
            return (MemorySegment) value;
        }
    }

    static final class Backend {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
        private static final VarHandle LAST_ERROR = CALL_STATE.varHandle(groupElement("GetLastError"));
        private static final Backend INSTANCE = new Backend();

        private final SymbolLookup kernel32 = SymbolLookup.libraryLookup("Kernel32.dll", Arena.global());
        private final SymbolLookup advapi32 = SymbolLookup.libraryLookup("Advapi32.dll", Arena.global());
        private final SymbolLookup userenv = SymbolLookup.libraryLookup("Userenv.dll", Arena.global());

        final MethodHandle getCurrentProcess = plain(kernel32, "GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
        final MethodHandle openProcessToken =
                captured(advapi32, "OpenProcessToken", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        final MethodHandle createRestrictedToken = captured(
                advapi32,
                "CreateRestrictedToken",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle setTokenInformation = captured(
                advapi32, "SetTokenInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        final MethodHandle convertStringSid =
                captured(advapi32, "ConvertStringSidToSidW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle getLengthSid = captured(advapi32, "GetLengthSid", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        final MethodHandle freeSid = plain(advapi32, "FreeSid", FunctionDescriptor.of(ADDRESS, ADDRESS));
        final MethodHandle createAppContainerProfile = captured(
                userenv,
                "CreateAppContainerProfile",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        final MethodHandle deleteAppContainerProfile =
                captured(userenv, "DeleteAppContainerProfile", FunctionDescriptor.of(JAVA_INT, ADDRESS));

        final MethodHandle getSecurityInfo = captured(
                advapi32,
                "GetSecurityInfo",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        final MethodHandle setSecurityInfo = captured(
                advapi32,
                "SetSecurityInfo",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        final MethodHandle setEntriesInAcl = captured(
                advapi32, "SetEntriesInAclW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        final MethodHandle descriptorToSddl = captured(
                advapi32,
                "ConvertSecurityDescriptorToStringSecurityDescriptorW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle sddlToDescriptor = captured(
                advapi32,
                "ConvertStringSecurityDescriptorToSecurityDescriptorW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle getSecurityDescriptorDacl = captured(
                advapi32,
                "GetSecurityDescriptorDacl",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        final MethodHandle getSecurityDescriptorControl = captured(
                advapi32, "GetSecurityDescriptorControl", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

        final MethodHandle createJobObject =
                captured(kernel32, "CreateJobObjectW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        final MethodHandle setJobInformation = captured(
                kernel32,
                "SetInformationJobObject",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        final MethodHandle assignProcessToJob =
                captured(kernel32, "AssignProcessToJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle terminateJob =
                captured(kernel32, "TerminateJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        final MethodHandle getStdHandle = captured(kernel32, "GetStdHandle", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        final MethodHandle setHandleInformation = captured(
                kernel32, "SetHandleInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        final MethodHandle createPipe =
                captured(kernel32, "CreatePipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        final MethodHandle readFile = captured(
                kernel32, "ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle writeFile = captured(
                kernel32, "WriteFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle cancelIoEx =
                captured(kernel32, "CancelIoEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle createPseudoConsole = captured(
                kernel32,
                "CreatePseudoConsole",
                FunctionDescriptor.of(JAVA_INT, COORD, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        final MethodHandle resizePseudoConsole =
                captured(kernel32, "ResizePseudoConsole", FunctionDescriptor.of(JAVA_INT, ADDRESS, COORD));
        final MethodHandle closePseudoConsole =
                plain(kernel32, "ClosePseudoConsole", FunctionDescriptor.ofVoid(ADDRESS));
        final MethodHandle initializeAttributeList = captured(
                kernel32,
                "InitializeProcThreadAttributeList",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        final MethodHandle updateAttribute = captured(
                kernel32,
                "UpdateProcThreadAttribute",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        final MethodHandle deleteAttributeList =
                plain(kernel32, "DeleteProcThreadAttributeList", FunctionDescriptor.ofVoid(ADDRESS));
        final MethodHandle createProcessAsUser = captured(
                advapi32,
                "CreateProcessAsUserW",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS,
                        ADDRESS, ADDRESS));
        final MethodHandle resumeThread = captured(kernel32, "ResumeThread", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        final MethodHandle waitForSingleObject =
                captured(kernel32, "WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        final MethodHandle getExitCodeProcess =
                captured(kernel32, "GetExitCodeProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle terminateProcess =
                captured(kernel32, "TerminateProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        final MethodHandle getFileAttributes =
                captured(kernel32, "GetFileAttributesW", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        final MethodHandle closeHandle = captured(kernel32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        final MethodHandle localFree = plain(kernel32, "LocalFree", FunctionDescriptor.of(ADDRESS, ADDRESS));

        static Backend instance() {
            return INSTANCE;
        }

        CallResult invoke(MethodHandle handle, Object... arguments) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(CALL_STATE);
                Object[] actual = new Object[arguments.length + 1];
                actual[0] = state;
                System.arraycopy(arguments, 0, actual, 1, arguments.length);
                Object value = handle.invokeWithArguments(actual);
                return new CallResult(value, (int) LAST_ERROR.get(state, 0L));
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IOException("cannot invoke Windows Sandbox API", failure);
            }
        }

        MemorySegment invokePlainAddress(MethodHandle handle, Object... arguments) throws IOException {
            try {
                return (MemorySegment) handle.invokeWithArguments(arguments);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IOException("cannot invoke Windows Sandbox API", failure);
            }
        }

        void invokePlainQuietly(MethodHandle handle, Object... arguments) {
            try {
                handle.invokeWithArguments(arguments);
            } catch (Throwable ignored) {
            }
        }

        private static MethodHandle captured(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(
                    lookup.findOrThrow(symbol), descriptor, Linker.Option.captureCallState("GetLastError"));
        }

        private static MethodHandle plain(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor);
        }
    }
}
