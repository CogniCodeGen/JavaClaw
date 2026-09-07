package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** 基于对象句柄的 Windows 文件绑定；NtStatus 与 Win32 last-error 不混用。 */
final class WindowsFileNative {
    private WindowsFileNative() {}

    static Backend backend() {
        WindowsSandboxNative.requireBackend();
        return Backend.INSTANCE;
    }

    static WindowsSandboxNative.CallResult call(MethodHandle method, Object... arguments) throws IOException {
        return WindowsSandboxNative.requireBackend().invoke(method, arguments);
    }

    static void ntCheck(String operation, String name, int status) throws IOException {
        if (status < 0) {
            int error = call(backend().statusToError, status).number();
            throw fileError(operation, name, error);
        }
    }

    static IOException fileError(String operation, String name, int error) {
        return switch (error) {
            case 2, 3 -> new NoSuchFileException(name, null, operation);
            case 5 -> new AccessDeniedException(name, null, operation);
            case 80, 183 -> new FileAlreadyExistsException(name, null, operation);
            default -> WindowsSandboxNative.error(operation + " (" + name + ")", error);
        };
    }

    static void requireSuccess(MethodHandle method, String operation, Object... arguments) throws IOException {
        var result = call(method, arguments);
        if (result.number() == 0) {
            throw WindowsSandboxNative.error(operation, result.error());
        }
    }

    static final class Backend {
        private static final Backend INSTANCE = new Backend();
        private final SymbolLookup kernel = SymbolLookup.libraryLookup("Kernel32.dll", Arena.global());
        private final SymbolLookup nt = SymbolLookup.libraryLookup("ntdll.dll", Arena.global());
        private final SymbolLookup shell = SymbolLookup.libraryLookup("Shell32.dll", Arena.global());
        private final SymbolLookup ole = SymbolLookup.libraryLookup("Ole32.dll", Arena.global());

        final MethodHandle createFile = bind(
                kernel,
                "CreateFileW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        final MethodHandle fileInformation =
                bind(kernel, "GetFileInformationByHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle fileType = bind(kernel, "GetFileType", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        final MethodHandle queryDosDevice =
                bind(kernel, "QueryDosDeviceW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        final MethodHandle setFilePointer = bind(
                kernel, "SetFilePointerEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
        final MethodHandle setEndOfFile = bind(kernel, "SetEndOfFile", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        final MethodHandle flush = bind(kernel, "FlushFileBuffers", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        final MethodHandle duplicate = bind(
                kernel,
                "DuplicateHandle",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        final MethodHandle ntCreate = bind(
                nt,
                "NtCreateFile",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        ADDRESS, JAVA_INT));
        final MethodHandle queryDirectory = bind(
                nt,
                "NtQueryDirectoryFile",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_BYTE,
                        ADDRESS, JAVA_BYTE));
        final MethodHandle setInformation = bind(
                nt,
                "NtSetInformationFile",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        final MethodHandle statusToError = bind(nt, "RtlNtStatusToDosError", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        final MethodHandle lockFile = bind(
                kernel,
                "LockFileEx",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        final MethodHandle unlockFile = bind(
                kernel,
                "UnlockFileEx",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        final MethodHandle knownFolder = bind(
                shell, "SHGetKnownFolderPath", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        final MethodHandle taskMemFree = bind(ole, "CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS));

        private static MethodHandle bind(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker()
                    .downcallHandle(
                            lookup.findOrThrow(name), descriptor, Linker.Option.captureCallState("GetLastError"));
        }
    }
}
