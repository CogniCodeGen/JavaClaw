package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** 在目标线程恢复前装配 AppContainer、Restricted Token、Job Object 与受控句柄。 */
final class WindowsProcessLauncher {
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_SUSPENDED = 0x00000004;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final int HANDLE_FLAG_INHERIT = 0x00000001;
    private static final int STD_INPUT_HANDLE = -10;
    private static final int STD_OUTPUT_HANDLE = -11;
    private static final int STD_ERROR_HANDLE = -12;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;
    private static final int FAILURE_EXIT = 126;
    private static final int TIMEOUT_EXIT = 124;

    private WindowsProcessLauncher() {}

    static int run(WindowsSandboxPaths.Prepared request, WindowsAppContainerScope scope) throws IOException {
        try (Arena arena = Arena.ofConfined();
                WindowsProcessSecurity security = WindowsProcessSecurity.open(request.limits(), arena)) {
            List<MemorySegment> standardHandles = inheritedStandardHandles();
            ProcessHandles child = null;
            try (WindowsProcessAttributes attributes = WindowsProcessAttributes.create(arena, 2)) {
                attributes.addHandleList(standardHandles);
                attributes.addSecurityCapabilities(scope.sid());
                child = createProcess(request, security, attributes.startup(standardHandles), true, arena);
                startContained(child, security);
                return await(child.process(), security, request.timeout(), arena);
            } catch (IOException | RuntimeException failure) {
                terminateFailedChild(child, security);
                throw failure;
            } finally {
                closeChild(child);
                setInheritance(standardHandles, false);
            }
        }
    }

    static WindowsPseudoConsole openPseudoConsole(
            WindowsSandboxPaths.Prepared request, WindowsAppContainerScope scope, int columns, int rows)
            throws IOException {
        Arena arena = Arena.ofConfined();
        WindowsProcessSecurity security = null;
        PseudoConsoleHandles handles = null;
        ProcessHandles child = null;
        boolean transferred = false;
        try {
            security = WindowsProcessSecurity.open(request.limits(), arena);
            handles = createPseudoConsole(columns, rows, arena);
            child = createPseudoConsoleProcess(request, scope, security, handles.pseudoConsole(), arena);
            startContained(child, security);
            closeAfterPseudoConsoleStart(handles, child);
            WindowsPseudoConsole session = new WindowsPseudoConsole(
                    handles.pseudoConsole(),
                    handles.inputWrite(),
                    handles.outputRead(),
                    child.process(),
                    security,
                    scope);
            transferred = true;
            return session;
        } catch (IOException | RuntimeException failure) {
            terminateFailedChild(child, security);
            throw failure;
        } finally {
            if (!transferred) {
                closePseudoConsoleHandles(handles);
                closeChild(child);
                closeSecurity(security);
            }
            arena.close();
        }
    }

    private static ProcessHandles createPseudoConsoleProcess(
            WindowsSandboxPaths.Prepared request,
            WindowsAppContainerScope scope,
            WindowsProcessSecurity security,
            MemorySegment pseudoConsole,
            Arena arena)
            throws IOException {
        try (WindowsProcessAttributes attributes = WindowsProcessAttributes.create(arena, 2)) {
            attributes.addPseudoConsole(pseudoConsole);
            attributes.addSecurityCapabilities(scope.sid());
            return createProcess(request, security, attributes.startup(List.of()), false, arena);
        }
    }

    private static ProcessHandles createProcess(
            WindowsSandboxPaths.Prepared request,
            WindowsProcessSecurity security,
            MemorySegment startup,
            boolean inheritHandles,
            Arena arena)
            throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment processInfo = arena.allocate(24, 8);
        int flags = EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT;
        var result = backend.invoke(
                backend.createProcessAsUser,
                security.token(),
                WindowsSandboxNative.wide(arena, request.executable().toString()),
                WindowsSandboxNative.wide(arena, WindowsCommandLine.encode(request.arguments())),
                MemorySegment.NULL,
                MemorySegment.NULL,
                inheritHandles ? 1 : 0,
                flags,
                WindowsEnvironmentBlock.encode(arena, request.environment()),
                WindowsSandboxNative.wide(arena, request.workingDirectory().toString()),
                startup,
                processInfo);
        if (result.number() == 0) {
            throw WindowsSandboxNative.error("CreateProcessAsUserW", result.error());
        }
        ProcessHandles handles = new ProcessHandles(
                WindowsSandboxNative.global(processInfo.get(ADDRESS, 0)),
                WindowsSandboxNative.global(processInfo.get(ADDRESS, 8)));
        try {
            WindowsSandboxNative.requireHandle(handles.process(), "created process", result.error());
            WindowsSandboxNative.requireHandle(handles.thread(), "created thread", result.error());
            return handles;
        } catch (IOException failure) {
            closeChild(handles);
            throw failure;
        }
    }

    private static List<MemorySegment> inheritedStandardHandles() throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        ArrayList<MemorySegment> handles = new ArrayList<>(3);
        for (int identifier : List.of(STD_INPUT_HANDLE, STD_OUTPUT_HANDLE, STD_ERROR_HANDLE)) {
            var result = backend.invoke(backend.getStdHandle, identifier);
            MemorySegment handle = WindowsSandboxNative.global(result.address());
            WindowsSandboxNative.requireHandle(handle, "GetStdHandle", result.error());
            handles.add(handle);
        }
        setInheritance(handles, true);
        return List.copyOf(handles);
    }

    private static void setInheritance(List<MemorySegment> handles, boolean inherit) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        IOException failure = null;
        for (MemorySegment handle : new LinkedHashSet<>(handles)) {
            var result = backend.invoke(
                    backend.setHandleInformation, handle, HANDLE_FLAG_INHERIT, inherit ? HANDLE_FLAG_INHERIT : 0);
            if (result.number() == 0 && failure == null) {
                failure = WindowsSandboxNative.error("SetHandleInformation", result.error());
            }
        }
        if (failure != null && inherit) {
            setInheritanceQuietly(handles, false);
            throw failure;
        }
    }

    private static void setInheritanceQuietly(List<MemorySegment> handles, boolean inherit) {
        try {
            setInheritance(handles, inherit);
        } catch (IOException ignored) {
            // 启动失败清理阶段只能尽力撤销继承位，原始异常仍是主要诊断。
        }
    }

    private static void startContained(ProcessHandles child, WindowsProcessSecurity security) throws IOException {
        security.assign(child.process());
        var backend = WindowsSandboxNative.requireBackend();
        var resumed = backend.invoke(backend.resumeThread, child.thread());
        if (resumed.number() == -1) {
            throw WindowsSandboxNative.error("ResumeThread", resumed.error());
        }
    }

    private static int await(MemorySegment process, WindowsProcessSecurity security, Duration timeout, Arena arena)
            throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        int waitMillis = Math.toIntExact(Math.max(1, timeout.toMillis()));
        var waited = backend.invoke(backend.waitForSingleObject, process, waitMillis);
        if (waited.number() == WAIT_TIMEOUT) {
            security.terminate(TIMEOUT_EXIT);
            backend.invoke(backend.waitForSingleObject, process, 5_000);
            return TIMEOUT_EXIT;
        }
        if (waited.number() != WAIT_OBJECT_0) {
            throw WindowsSandboxNative.error("WaitForSingleObject", waited.error());
        }
        MemorySegment exitCode = arena.allocate(JAVA_INT);
        var loaded = backend.invoke(backend.getExitCodeProcess, process, exitCode);
        if (loaded.number() == 0) {
            throw WindowsSandboxNative.error("GetExitCodeProcess", loaded.error());
        }
        return exitCode.get(JAVA_INT, 0);
    }

    private static PseudoConsoleHandles createPseudoConsole(int columns, int rows, Arena arena) throws IOException {
        Pipe input = createPipe(arena, "ConPTY input");
        Pipe output = null;
        MemorySegment pseudoConsole = MemorySegment.NULL;
        try {
            output = createPipe(arena, "ConPTY output");
            MemorySegment pseudoOut = arena.allocate(ADDRESS);
            var backend = WindowsSandboxNative.requireBackend();
            var created = backend.invoke(
                    backend.createPseudoConsole,
                    WindowsSandboxNative.coord(arena, columns, rows),
                    input.read(),
                    output.write(),
                    0,
                    pseudoOut);
            if (created.number() != 0) {
                throw WindowsSandboxNative.status("CreatePseudoConsole", created.number());
            }
            pseudoConsole = WindowsSandboxNative.global(pseudoOut.get(ADDRESS, 0));
            WindowsSandboxNative.requireHandle(pseudoConsole, "CreatePseudoConsole", created.error());
            return new PseudoConsoleHandles(pseudoConsole, input.read(), input.write(), output.read(), output.write());
        } catch (IOException | RuntimeException failure) {
            WindowsSandboxNative.closePseudoConsole(pseudoConsole);
            closePipe(input);
            closePipe(output);
            throw failure;
        }
    }

    private static Pipe createPipe(Arena arena, String name) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment read = arena.allocate(ADDRESS);
        MemorySegment write = arena.allocate(ADDRESS);
        var created = backend.invoke(backend.createPipe, read, write, MemorySegment.NULL, 0);
        if (created.number() == 0) {
            throw WindowsSandboxNative.error("CreatePipe(" + name + ")", created.error());
        }
        return new Pipe(
                WindowsSandboxNative.global(read.get(ADDRESS, 0)), WindowsSandboxNative.global(write.get(ADDRESS, 0)));
    }

    private static void closeAfterPseudoConsoleStart(PseudoConsoleHandles handles, ProcessHandles child) {
        WindowsSandboxNative.closeHandleQuietly(handles.inputRead());
        WindowsSandboxNative.closeHandleQuietly(handles.outputWrite());
        WindowsSandboxNative.closeHandleQuietly(child.thread());
    }

    private static void terminateFailedChild(ProcessHandles child, WindowsProcessSecurity security) {
        if (security != null) {
            security.terminate(FAILURE_EXIT);
        }
        if (child != null) {
            WindowsSandboxNative.terminateProcessQuietly(child.process(), FAILURE_EXIT);
        }
    }

    private static void closeChild(ProcessHandles child) {
        if (child != null) {
            WindowsSandboxNative.closeHandleQuietly(child.thread());
            WindowsSandboxNative.closeHandleQuietly(child.process());
        }
    }

    private static void closeSecurity(WindowsProcessSecurity security) {
        if (security != null) {
            security.close();
        }
    }

    private static void closePseudoConsoleHandles(PseudoConsoleHandles handles) {
        if (handles == null) {
            return;
        }
        WindowsSandboxNative.closePseudoConsole(handles.pseudoConsole());
        closePipe(new Pipe(handles.inputRead(), handles.inputWrite()));
        closePipe(new Pipe(handles.outputRead(), handles.outputWrite()));
    }

    private static void closePipe(Pipe pipe) {
        if (pipe != null) {
            WindowsSandboxNative.closeHandleQuietly(pipe.read());
            WindowsSandboxNative.closeHandleQuietly(pipe.write());
        }
    }

    private record ProcessHandles(MemorySegment process, MemorySegment thread) {}

    private record Pipe(MemorySegment read, MemorySegment write) {}

    private record PseudoConsoleHandles(
            MemorySegment pseudoConsole,
            MemorySegment inputRead,
            MemorySegment inputWrite,
            MemorySegment outputRead,
            MemorySegment outputWrite) {}
}
