package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Windows-only AppContainer launcher. The caller is the isolated Sandbox Launcher JVM; the target inherits only its
 * standard handles and is placed in a kill-on-close Job Object before resume.
 */
public final class WindowsSandbox {
    private static final int TOKEN_ASSIGN_PRIMARY = 0x0001;
    private static final int TOKEN_DUPLICATE = 0x0002;
    private static final int TOKEN_QUERY = 0x0008;
    private static final int TOKEN_ADJUST_DEFAULT = 0x0080;
    private static final int TOKEN_ADJUST_SESSIONID = 0x0100;
    private static final int DISABLE_MAX_PRIVILEGE = 0x0001;
    private static final int TOKEN_INTEGRITY_LEVEL = 25;
    private static final int SE_GROUP_INTEGRITY = 0x00000020;

    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_SUSPENDED = 0x00000004;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final long PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002L;
    private static final long PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x00020009L;
    private static final long PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016L;
    private static final int HANDLE_FLAG_INHERIT = 0x00000001;
    private static final int STD_INPUT_HANDLE = -10;
    private static final int STD_OUTPUT_HANDLE = -11;
    private static final int STD_ERROR_HANDLE = -12;

    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_BASIC_UI_RESTRICTIONS = 4;
    private static final int JOB_OBJECT_LIMIT_PROCESS_TIME = 0x00000002;
    private static final int JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x00000008;
    private static final int JOB_OBJECT_LIMIT_PROCESS_MEMORY = 0x00000100;
    private static final int JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION = 0x00000400;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int JOB_OBJECT_UILIMIT_ALL = 0x000000ff;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;
    private static final int ERROR_BROKEN_PIPE = 109;
    private static final int ERROR_NO_DATA = 232;

    private static final int SE_FILE_OBJECT = 1;
    private static final int DACL_SECURITY_INFORMATION = 0x00000004;
    private static final int PROTECTED_DACL_SECURITY_INFORMATION = 0x80000000;
    private static final int UNPROTECTED_DACL_SECURITY_INFORMATION = 0x20000000;
    private static final int SE_DACL_PROTECTED = 0x1000;
    private static final int SDDL_REVISION_1 = 1;
    private static final int GRANT_ACCESS = 1;
    private static final int DENY_ACCESS = 3;
    private static final int SUB_CONTAINERS_AND_OBJECTS_INHERIT = 3;
    private static final int TRUSTEE_IS_SID = 0;
    private static final int TRUSTEE_IS_WELL_KNOWN_GROUP = 5;
    private static final int FILE_GENERIC_READ = 0x00120089;
    private static final int FILE_GENERIC_WRITE = 0x00120116;
    private static final int FILE_GENERIC_EXECUTE = 0x001200a0;
    private static final int DELETE = 0x00010000;
    private static final int GENERIC_ALL = 0x10000000;
    private static final int INVALID_FILE_ATTRIBUTES = -1;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400;

    private static final long DEFAULT_MEMORY_LIMIT = 2L * 1024L * 1024L * 1024L;
    private static final int DEFAULT_PROCESS_LIMIT = 32;

    private static final MemoryLayout TOKEN_MANDATORY_LABEL = MemoryLayout.structLayout(
            ADDRESS.withName("sid"), JAVA_INT.withName("attributes"), MemoryLayout.paddingLayout(4));
    private static final MemoryLayout SECURITY_CAPABILITIES = MemoryLayout.structLayout(
            ADDRESS.withName("appContainerSid"), ADDRESS.withName("capabilities"),
            JAVA_INT.withName("capabilityCount"), JAVA_INT.withName("reserved"));
    private static final MemoryLayout COORD =
            MemoryLayout.structLayout(JAVA_SHORT.withName("x"), JAVA_SHORT.withName("y"));
    private static final MemoryLayout EXPLICIT_ACCESS = MemoryLayout.structLayout(
            JAVA_INT.withName("permissions"), JAVA_INT.withName("mode"),
            JAVA_INT.withName("inheritance"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("multipleTrustee"), JAVA_INT.withName("multipleOperation"),
            JAVA_INT.withName("trusteeForm"), JAVA_INT.withName("trusteeType"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("trusteeName"));
    private static final MemoryLayout STARTUP_INFO_EX = MemoryLayout.structLayout(
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

    private WindowsSandbox() {}

    /** 检查 Windows 64 位 ABI 及必需绑定；false 表示能力不可用，true 仍须在启动时执行完整 Token、ACL 和 Job 校验。 */
    public static boolean isSupported() {
        if (!isWindows() || ADDRESS.byteSize() != 8) {
            return false;
        }
        try {
            Bindings.ensureLoaded();
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * 在独立 Launcher 内以 Restricted Token、AppContainer 和 Job 执行非空 argv 并返回退出码。工作目录必须存在，timeout 必须为正且不超过 24 小时；临时 ACL
     * 在结束时恢复，清理失败不得视为成功。
     *
     * @throws IOException 原生启动、执行或权限清理失败
     */
    public static int run(
            List<String> arguments,
            Path workingDirectory,
            Set<Path> readableRoots,
            Set<Path> writableRoots,
            Set<Path> protectedRoots,
            Duration timeout)
            throws IOException {
        requireSupported();
        List<String> argv = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("Windows sandbox argv is invalid");
        }
        Path cwd = canonicalExisting(workingDirectory, "working directory");
        Duration boundedTimeout = Objects.requireNonNull(timeout, "timeout");
        if (boundedTimeout.isZero()
                || boundedTimeout.isNegative()
                || boundedTimeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Windows sandbox timeout is invalid");
        }

        String profileName =
                "JavaClaw.Sandbox.v4." + UUID.randomUUID().toString().replace("-", "");
        MemorySegment profileSid = MemorySegment.NULL;
        ArrayList<AclSnapshot> aclSnapshots = new ArrayList<>();
        Throwable executionFailure = null;
        Integer exitCode = null;
        try {
            profileSid = createProfile(profileName);
            Map<Path, Integer> grants = grants(cwd, argv, readableRoots, writableRoots);
            for (Map.Entry<Path, Integer> grant : grants.entrySet()) {
                aclSnapshots.add(changeAcl(grant.getKey(), profileSid, grant.getValue(), GRANT_ACCESS));
            }
            ArrayList<Path> protectedPaths = new ArrayList<>();
            for (Path requested : protectedRoots) {
                Path normalized = requested.toAbsolutePath().normalize();
                boolean exposed = writableGrantExposes(grants, normalized);
                if (!exposed) {
                    continue;
                }
                if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Windows cannot protect a non-existing writable path: " + normalized);
                }
                protectedPaths.add(canonicalExisting(normalized, "protected root"));
            }
            protectedPaths.sort(Comparator.comparingInt(Path::getNameCount));
            for (Path protectedRoot : protectedPaths) {
                aclSnapshots.add(changeAcl(protectedRoot, profileSid, GENERIC_ALL, DENY_ACCESS));
            }
            exitCode = createRestrictedProcess(
                    argv, cwd, profileSid, boundedTimeout, DEFAULT_MEMORY_LIMIT, DEFAULT_PROCESS_LIMIT);
        } catch (Throwable failure) {
            executionFailure = failure;
        }

        IOException cleanupFailure = restoreAcls(aclSnapshots);
        if (!profileSid.equals(MemorySegment.NULL)) {
            try {
                callAddress(Bindings.FREE_SID, profileSid);
            } catch (IOException failure) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure;
                } else {
                    cleanupFailure.addSuppressed(failure);
                }
            }
        }
        try {
            int deleted = callInt(Bindings.DELETE_APP_CONTAINER_PROFILE, wide(Arena.ofAuto(), profileName));
            if (deleted != 0 && cleanupFailure == null) {
                cleanupFailure = new IOException(
                        "DeleteAppContainerProfile failed (HRESULT 0x" + Integer.toHexString(deleted) + ")");
            }
        } catch (Throwable failure) {
            IOException value = asIo("cannot delete AppContainer profile", failure);
            if (cleanupFailure == null) {
                cleanupFailure = value;
            } else {
                cleanupFailure.addSuppressed(value);
            }
        }

        if (executionFailure != null) {
            if (cleanupFailure != null) {
                executionFailure.addSuppressed(cleanupFailure);
            }
            if (executionFailure instanceof IOException io) {
                throw io;
            }
            if (executionFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Windows sandbox execution failed", executionFailure);
        }
        if (cleanupFailure != null) {
            throw new AclRestorationException(
                    "Windows sandbox ACL restoration failed; lock the Workspace", cleanupFailure);
        }
        return Objects.requireNonNull(exitCode, "exitCode");
    }

    /** Opens a real ConPTY attached to an AppContainer/Job-contained target process. */
    public static PseudoConsoleSession openPseudoConsole(
            List<String> arguments,
            Path workingDirectory,
            Set<Path> readableRoots,
            Set<Path> writableRoots,
            Set<Path> protectedRoots,
            Duration timeout,
            int columns,
            int rows)
            throws IOException {
        requireSupported();
        List<String> argv = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("Windows sandbox argv is invalid");
        }
        validateConsoleSize(columns, rows);
        Path cwd = canonicalExisting(workingDirectory, "working directory");
        Duration boundedTimeout = Objects.requireNonNull(timeout, "timeout");
        if (boundedTimeout.isZero()
                || boundedTimeout.isNegative()
                || boundedTimeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Windows sandbox timeout is invalid");
        }
        PreparedScope scope = prepareScope(argv, cwd, readableRoots, writableRoots, protectedRoots);
        try {
            return createRestrictedPseudoConsole(
                    argv, cwd, scope, boundedTimeout, DEFAULT_MEMORY_LIMIT, DEFAULT_PROCESS_LIMIT, columns, rows);
        } catch (Throwable failure) {
            try {
                scope.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Windows ConPTY creation failed", failure);
        }
    }

    private static PreparedScope prepareScope(
            List<String> argv, Path cwd, Set<Path> readableRoots, Set<Path> writableRoots, Set<Path> protectedRoots)
            throws IOException {
        String profileName =
                "JavaClaw.Sandbox.v4." + UUID.randomUUID().toString().replace("-", "");
        MemorySegment profileSid = createProfile(profileName);
        ArrayList<AclSnapshot> aclSnapshots = new ArrayList<>();
        try {
            Map<Path, Integer> grants = grants(cwd, argv, readableRoots, writableRoots);
            for (Map.Entry<Path, Integer> grant : grants.entrySet()) {
                aclSnapshots.add(changeAcl(grant.getKey(), profileSid, grant.getValue(), GRANT_ACCESS));
            }
            ArrayList<Path> protectedPaths = new ArrayList<>();
            for (Path requested : protectedRoots) {
                Path normalized = requested.toAbsolutePath().normalize();
                boolean exposed = writableGrantExposes(grants, normalized);
                if (!exposed) {
                    continue;
                }
                if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Windows cannot protect a non-existing writable path: " + normalized);
                }
                protectedPaths.add(canonicalExisting(normalized, "protected root"));
            }
            protectedPaths.sort(Comparator.comparingInt(Path::getNameCount));
            for (Path protectedRoot : protectedPaths) {
                aclSnapshots.add(changeAcl(protectedRoot, profileSid, GENERIC_ALL, DENY_ACCESS));
            }
            return new PreparedScope(profileName, profileSid, aclSnapshots);
        } catch (Throwable failure) {
            try {
                new PreparedScope(profileName, profileSid, aclSnapshots).close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("cannot prepare Windows sandbox scope", failure);
        }
    }

    private static Map<Path, Integer> grants(
            Path cwd, List<String> argv, Set<Path> readableRoots, Set<Path> writableRoots) throws IOException {
        LinkedHashMap<Path, Integer> result = new LinkedHashMap<>();
        for (Path root : Objects.requireNonNull(readableRoots, "readableRoots")) {
            result.put(canonicalExisting(root, "readable root"), FILE_GENERIC_READ | FILE_GENERIC_EXECUTE);
        }
        for (Path root : Objects.requireNonNull(writableRoots, "writableRoots")) {
            result.put(
                    canonicalExisting(root, "writable root"),
                    FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | DELETE);
        }
        result.putIfAbsent(cwd, FILE_GENERIC_READ | FILE_GENERIC_EXECUTE);
        Path requestedExecutable;
        try {
            requestedExecutable = Path.of(argv.getFirst());
        } catch (RuntimeException invalid) {
            throw new IOException("Windows command executable path is invalid", invalid);
        }
        if (!requestedExecutable.isAbsolute()) {
            throw new IOException("Windows sandbox commands require an absolute executable path");
        }
        Path executable = canonicalExisting(requestedExecutable, "command executable");
        boolean granted = result.keySet().stream().anyMatch(executable::startsWith);
        if (!granted && !trustedInfrastructureExecutable(executable)) {
            throw new IOException("Windows command executable is outside granted roots: " + executable);
        }
        return Map.copyOf(result);
    }

    private static boolean writableGrantExposes(Map<Path, Integer> grants, Path protectedPath) {
        return grants.entrySet().stream()
                .anyMatch(entry -> (entry.getValue() & FILE_GENERIC_WRITE) == FILE_GENERIC_WRITE
                        && protectedPath.startsWith(entry.getKey()));
    }

    private static boolean trustedInfrastructureExecutable(Path executable) {
        ArrayList<Path> roots = new ArrayList<>();
        roots.add(Path.of(System.getProperty("java.home", "C:\\JavaClawRuntime"))
                .toAbsolutePath()
                .normalize());
        String windows = System.getenv("SystemRoot");
        roots.add(Path.of(windows == null || windows.isBlank() ? "C:\\Windows" : windows)
                .toAbsolutePath()
                .normalize());
        return roots.stream().anyMatch(executable::startsWith);
    }

    private static MemorySegment createProfile(String name) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sidOut = arena.allocate(ADDRESS);
            int result = callInt(
                    Bindings.CREATE_APP_CONTAINER_PROFILE,
                    wide(arena, name),
                    wide(arena, "JavaClaw Sandbox"),
                    wide(arena, "Ephemeral JavaClaw Agent sandbox"),
                    MemorySegment.NULL,
                    0,
                    sidOut);
            if (result != 0) {
                throw new IOException(
                        "CreateAppContainerProfile failed (HRESULT 0x" + Integer.toHexString(result) + ")");
            }
            MemorySegment sid = sidOut.get(ADDRESS, 0);
            if (sid.equals(MemorySegment.NULL)) {
                throw new IOException("CreateAppContainerProfile returned no SID");
            }
            // The out-parameter lives in this confined arena; retain only the native address
            // with a global zero-length view because the SID itself is owned by Windows.
            return MemorySegment.ofAddress(sid.address());
        }
    }

    private static int createRestrictedProcess(
            List<String> argv,
            Path cwd,
            MemorySegment appContainerSid,
            Duration timeout,
            long memoryLimit,
            int processLimit)
            throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tokenOut = arena.allocate(ADDRESS);
            int tokenAccess = TOKEN_ASSIGN_PRIMARY
                    | TOKEN_DUPLICATE
                    | TOKEN_QUERY
                    | TOKEN_ADJUST_DEFAULT
                    | TOKEN_ADJUST_SESSIONID;
            if (callInt(Bindings.OPEN_PROCESS_TOKEN, callAddress(Bindings.GET_CURRENT_PROCESS), tokenAccess, tokenOut)
                    == 0) {
                throw failure("OpenProcessToken failed");
            }
            MemorySegment sourceToken = tokenOut.get(ADDRESS, 0);
            MemorySegment restrictedToken = MemorySegment.NULL;
            MemorySegment job = MemorySegment.NULL;
            MemorySegment process = MemorySegment.NULL;
            MemorySegment thread = MemorySegment.NULL;
            MemorySegment attributeList = MemorySegment.NULL;
            MemorySegment stdin = MemorySegment.NULL;
            MemorySegment stdout = MemorySegment.NULL;
            MemorySegment stderr = MemorySegment.NULL;
            try {
                MemorySegment restrictedOut = arena.allocate(ADDRESS);
                if (callInt(
                                Bindings.CREATE_RESTRICTED_TOKEN,
                                sourceToken,
                                DISABLE_MAX_PRIVILEGE,
                                0,
                                MemorySegment.NULL,
                                0,
                                MemorySegment.NULL,
                                0,
                                MemorySegment.NULL,
                                restrictedOut)
                        == 0) {
                    throw failure("CreateRestrictedToken failed");
                }
                restrictedToken = restrictedOut.get(ADDRESS, 0);
                applyLowIntegrity(restrictedToken, arena);

                job = callAddress(Bindings.CREATE_JOB_OBJECT, MemorySegment.NULL, MemorySegment.NULL);
                requireHandle(job, "CreateJobObjectW");
                configureJob(job, timeout, memoryLimit, processLimit, arena);

                stdin = callAddress(Bindings.GET_STD_HANDLE, STD_INPUT_HANDLE);
                stdout = callAddress(Bindings.GET_STD_HANDLE, STD_OUTPUT_HANDLE);
                stderr = callAddress(Bindings.GET_STD_HANDLE, STD_ERROR_HANDLE);
                requireHandle(stdin, "GetStdHandle(stdin)");
                requireHandle(stdout, "GetStdHandle(stdout)");
                requireHandle(stderr, "GetStdHandle(stderr)");
                for (MemorySegment handle : List.of(stdin, stdout, stderr)) {
                    if (callInt(Bindings.SET_HANDLE_INFORMATION, handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT)
                            == 0) {
                        throw failure("SetHandleInformation failed");
                    }
                }

                MemorySegment attributeBytes = arena.allocate(JAVA_LONG);
                callInt(Bindings.INITIALIZE_ATTRIBUTE_LIST, MemorySegment.NULL, 2, 0, attributeBytes);
                long bytes = attributeBytes.get(JAVA_LONG, 0);
                if (bytes < 1 || bytes > 1024 * 1024) {
                    throw new IOException("invalid process attribute-list size: " + bytes);
                }
                attributeList = arena.allocate(bytes, ADDRESS.byteAlignment());
                if (callInt(Bindings.INITIALIZE_ATTRIBUTE_LIST, attributeList, 2, 0, attributeBytes) == 0) {
                    throw failure("InitializeProcThreadAttributeList failed");
                }

                MemorySegment handles = arena.allocate(ADDRESS.byteSize() * 3, ADDRESS.byteAlignment());
                handles.setAtIndex(ADDRESS, 0, stdin);
                handles.setAtIndex(ADDRESS, 1, stdout);
                handles.setAtIndex(ADDRESS, 2, stderr);
                updateAttribute(attributeList, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handles, handles.byteSize());

                MemorySegment capabilities = arena.allocate(SECURITY_CAPABILITIES);
                capabilities.set(ADDRESS, 0, appContainerSid);
                capabilities.set(ADDRESS, 8, MemorySegment.NULL);
                capabilities.set(JAVA_INT, 16, 0);
                capabilities.set(JAVA_INT, 20, 0);
                updateAttribute(
                        attributeList,
                        PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES,
                        capabilities,
                        SECURITY_CAPABILITIES.byteSize());

                MemorySegment startup = arena.allocate(STARTUP_INFO_EX);
                startup.set(JAVA_INT, 0, Math.toIntExact(STARTUP_INFO_EX.byteSize()));
                startup.set(JAVA_INT, 60, STARTF_USESTDHANDLES);
                startup.set(ADDRESS, 80, stdin);
                startup.set(ADDRESS, 88, stdout);
                startup.set(ADDRESS, 96, stderr);
                startup.set(ADDRESS, 104, attributeList);
                MemorySegment processInfo = arena.allocate(24, 8);
                int flags = EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT;
                MemorySegment commandLine = wide(arena, commandLine(argv));
                int created = callInt(
                        Bindings.CREATE_PROCESS_AS_USER,
                        restrictedToken,
                        MemorySegment.NULL,
                        commandLine,
                        MemorySegment.NULL,
                        MemorySegment.NULL,
                        1,
                        flags,
                        MemorySegment.NULL,
                        wide(arena, cwd.toString()),
                        startup,
                        processInfo);
                if (created == 0) {
                    throw failure("CreateProcessAsUserW failed");
                }
                process = processInfo.get(ADDRESS, 0);
                thread = processInfo.get(ADDRESS, 8);
                requireHandle(process, "CreateProcessAsUserW process");
                requireHandle(thread, "CreateProcessAsUserW thread");
                if (callInt(Bindings.ASSIGN_PROCESS_TO_JOB, job, process) == 0) {
                    callIntUnchecked(Bindings.TERMINATE_PROCESS, process, 126);
                    throw failure("AssignProcessToJobObject failed");
                }
                int resumed = callInt(Bindings.RESUME_THREAD, thread);
                if (resumed == -1) {
                    callIntUnchecked(Bindings.TERMINATE_JOB, job, 126);
                    throw failure("ResumeThread failed");
                }
                long millis = Math.min(0xfffffff0L, Math.max(1L, timeout.toMillis()));
                int wait = callInt(Bindings.WAIT_FOR_SINGLE_OBJECT, process, (int) millis);
                if (wait == WAIT_TIMEOUT) {
                    callIntUnchecked(Bindings.TERMINATE_JOB, job, 124);
                    callIntUnchecked(Bindings.WAIT_FOR_SINGLE_OBJECT, process, 5_000);
                    return 124;
                }
                if (wait != WAIT_OBJECT_0) {
                    throw failure("WaitForSingleObject failed");
                }
                MemorySegment exitCode = arena.allocate(JAVA_INT);
                if (callInt(Bindings.GET_EXIT_CODE_PROCESS, process, exitCode) == 0) {
                    throw failure("GetExitCodeProcess failed");
                }
                return exitCode.get(JAVA_INT, 0);
            } finally {
                if (!attributeList.equals(MemorySegment.NULL)) {
                    callVoidUnchecked(Bindings.DELETE_ATTRIBUTE_LIST, attributeList);
                }
                for (MemorySegment handle : List.of(stdin, stdout, stderr)) {
                    if (!handle.equals(MemorySegment.NULL)) {
                        callIntUnchecked(Bindings.SET_HANDLE_INFORMATION, handle, HANDLE_FLAG_INHERIT, 0);
                    }
                }
                closeHandle(thread);
                closeHandle(process);
                closeHandle(job);
                closeHandle(restrictedToken);
                closeHandle(sourceToken);
            }
        }
    }

    private static PseudoConsoleSession createRestrictedPseudoConsole(
            List<String> argv,
            Path cwd,
            PreparedScope scope,
            Duration timeout,
            long memoryLimit,
            int processLimit,
            int columns,
            int rows)
            throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sourceToken = MemorySegment.NULL;
            MemorySegment restrictedToken = MemorySegment.NULL;
            MemorySegment job = MemorySegment.NULL;
            MemorySegment process = MemorySegment.NULL;
            MemorySegment thread = MemorySegment.NULL;
            MemorySegment attributeList = MemorySegment.NULL;
            MemorySegment pseudoConsole = MemorySegment.NULL;
            MemorySegment inputRead = MemorySegment.NULL;
            MemorySegment inputWrite = MemorySegment.NULL;
            MemorySegment outputRead = MemorySegment.NULL;
            MemorySegment outputWrite = MemorySegment.NULL;
            boolean transferred = false;
            try {
                MemorySegment tokenOut = arena.allocate(ADDRESS);
                int tokenAccess = TOKEN_ASSIGN_PRIMARY
                        | TOKEN_DUPLICATE
                        | TOKEN_QUERY
                        | TOKEN_ADJUST_DEFAULT
                        | TOKEN_ADJUST_SESSIONID;
                if (callInt(
                                Bindings.OPEN_PROCESS_TOKEN,
                                callAddress(Bindings.GET_CURRENT_PROCESS),
                                tokenAccess,
                                tokenOut)
                        == 0) {
                    throw failure("OpenProcessToken failed");
                }
                sourceToken = globalHandle(tokenOut.get(ADDRESS, 0));
                MemorySegment restrictedOut = arena.allocate(ADDRESS);
                if (callInt(
                                Bindings.CREATE_RESTRICTED_TOKEN,
                                sourceToken,
                                DISABLE_MAX_PRIVILEGE,
                                0,
                                MemorySegment.NULL,
                                0,
                                MemorySegment.NULL,
                                0,
                                MemorySegment.NULL,
                                restrictedOut)
                        == 0) {
                    throw failure("CreateRestrictedToken failed");
                }
                restrictedToken = globalHandle(restrictedOut.get(ADDRESS, 0));
                applyLowIntegrity(restrictedToken, arena);

                job = callAddress(Bindings.CREATE_JOB_OBJECT, MemorySegment.NULL, MemorySegment.NULL);
                requireHandle(job, "CreateJobObjectW");
                configureJob(job, timeout, memoryLimit, processLimit, arena);

                MemorySegment inputReadOut = arena.allocate(ADDRESS);
                MemorySegment inputWriteOut = arena.allocate(ADDRESS);
                if (callInt(Bindings.CREATE_PIPE, inputReadOut, inputWriteOut, MemorySegment.NULL, 0) == 0) {
                    throw failure("CreatePipe(ConPTY input) failed");
                }
                inputRead = globalHandle(inputReadOut.get(ADDRESS, 0));
                inputWrite = globalHandle(inputWriteOut.get(ADDRESS, 0));
                MemorySegment outputReadOut = arena.allocate(ADDRESS);
                MemorySegment outputWriteOut = arena.allocate(ADDRESS);
                if (callInt(Bindings.CREATE_PIPE, outputReadOut, outputWriteOut, MemorySegment.NULL, 0) == 0) {
                    throw failure("CreatePipe(ConPTY output) failed");
                }
                outputRead = globalHandle(outputReadOut.get(ADDRESS, 0));
                outputWrite = globalHandle(outputWriteOut.get(ADDRESS, 0));

                MemorySegment pseudoOut = arena.allocate(ADDRESS);
                int pseudoStatus = callInt(
                        Bindings.CREATE_PSEUDO_CONSOLE,
                        coord(arena, columns, rows),
                        inputRead,
                        outputWrite,
                        0,
                        pseudoOut);
                if (pseudoStatus != 0) {
                    throw new IOException(
                            "CreatePseudoConsole failed (HRESULT 0x" + Integer.toHexString(pseudoStatus) + ")");
                }
                pseudoConsole = globalHandle(pseudoOut.get(ADDRESS, 0));
                requireHandle(pseudoConsole, "CreatePseudoConsole");

                MemorySegment attributeBytes = arena.allocate(JAVA_LONG);
                callInt(Bindings.INITIALIZE_ATTRIBUTE_LIST, MemorySegment.NULL, 2, 0, attributeBytes);
                long bytes = attributeBytes.get(JAVA_LONG, 0);
                if (bytes < 1 || bytes > 1024 * 1024) {
                    throw new IOException("invalid process attribute-list size: " + bytes);
                }
                attributeList = arena.allocate(bytes, ADDRESS.byteAlignment());
                if (callInt(Bindings.INITIALIZE_ATTRIBUTE_LIST, attributeList, 2, 0, attributeBytes) == 0) {
                    throw failure("InitializeProcThreadAttributeList failed");
                }
                updateAttribute(attributeList, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, pseudoConsole, ADDRESS.byteSize());
                MemorySegment capabilities = arena.allocate(SECURITY_CAPABILITIES);
                capabilities.set(ADDRESS, 0, scope.profileSid());
                capabilities.set(ADDRESS, 8, MemorySegment.NULL);
                capabilities.set(JAVA_INT, 16, 0);
                capabilities.set(JAVA_INT, 20, 0);
                updateAttribute(
                        attributeList,
                        PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES,
                        capabilities,
                        SECURITY_CAPABILITIES.byteSize());

                MemorySegment startup = arena.allocate(STARTUP_INFO_EX);
                startup.set(JAVA_INT, 0, Math.toIntExact(STARTUP_INFO_EX.byteSize()));
                startup.set(ADDRESS, 104, attributeList);
                MemorySegment processInfo = arena.allocate(24, 8);
                int flags = EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT;
                MemorySegment commandLine = wide(arena, commandLine(argv));
                int created = callInt(
                        Bindings.CREATE_PROCESS_AS_USER,
                        restrictedToken,
                        MemorySegment.NULL,
                        commandLine,
                        MemorySegment.NULL,
                        MemorySegment.NULL,
                        0,
                        flags,
                        MemorySegment.NULL,
                        wide(arena, cwd.toString()),
                        startup,
                        processInfo);
                if (created == 0) {
                    throw failure("CreateProcessAsUserW(ConPTY) failed");
                }
                process = globalHandle(processInfo.get(ADDRESS, 0));
                thread = globalHandle(processInfo.get(ADDRESS, 8));
                requireHandle(process, "CreateProcessAsUserW process");
                requireHandle(thread, "CreateProcessAsUserW thread");
                if (callInt(Bindings.ASSIGN_PROCESS_TO_JOB, job, process) == 0) {
                    callIntUnchecked(Bindings.TERMINATE_PROCESS, process, 126);
                    throw failure("AssignProcessToJobObject failed");
                }
                if (callInt(Bindings.RESUME_THREAD, thread) == -1) {
                    callIntUnchecked(Bindings.TERMINATE_JOB, job, 126);
                    throw failure("ResumeThread failed");
                }

                // ConPTY duplicated these server ends during creation; retaining them would
                // prevent EOF after ClosePseudoConsole.
                closeHandle(inputRead);
                inputRead = MemorySegment.NULL;
                closeHandle(outputWrite);
                outputWrite = MemorySegment.NULL;
                closeHandle(thread);
                thread = MemorySegment.NULL;
                closeHandle(restrictedToken);
                restrictedToken = MemorySegment.NULL;
                closeHandle(sourceToken);
                sourceToken = MemorySegment.NULL;
                PseudoConsoleSession session =
                        new PseudoConsoleSession(pseudoConsole, inputWrite, outputRead, process, job, scope);
                transferred = true;
                return session;
            } finally {
                if (!attributeList.equals(MemorySegment.NULL)) {
                    callVoidUnchecked(Bindings.DELETE_ATTRIBUTE_LIST, attributeList);
                }
                if (!transferred) {
                    if (!job.equals(MemorySegment.NULL)) {
                        callIntUnchecked(Bindings.TERMINATE_JOB, job, 126);
                    }
                    closePseudoConsole(pseudoConsole);
                    closeHandle(inputRead);
                    closeHandle(inputWrite);
                    closeHandle(outputRead);
                    closeHandle(outputWrite);
                    closeHandle(thread);
                    closeHandle(process);
                    closeHandle(job);
                    closeHandle(restrictedToken);
                    closeHandle(sourceToken);
                }
            }
        }
    }

    private static void applyLowIntegrity(MemorySegment token, Arena arena) throws IOException {
        MemorySegment sidOut = arena.allocate(ADDRESS);
        if (callInt(Bindings.CONVERT_STRING_SID, wide(arena, "S-1-16-4096"), sidOut) == 0) {
            throw failure("ConvertStringSidToSidW failed");
        }
        MemorySegment lowSid = sidOut.get(ADDRESS, 0);
        try {
            MemorySegment label = arena.allocate(TOKEN_MANDATORY_LABEL);
            label.set(ADDRESS, 0, lowSid);
            label.set(JAVA_INT, 8, SE_GROUP_INTEGRITY);
            int sidLength = callInt(Bindings.GET_LENGTH_SID, lowSid);
            if (sidLength < 1
                    || callInt(
                                    Bindings.SET_TOKEN_INFORMATION,
                                    token,
                                    TOKEN_INTEGRITY_LEVEL,
                                    label,
                                    Math.toIntExact(TOKEN_MANDATORY_LABEL.byteSize()) + sidLength)
                            == 0) {
                throw failure("SetTokenInformation(TokenIntegrityLevel) failed");
            }
        } finally {
            callAddress(Bindings.LOCAL_FREE, lowSid);
        }
    }

    private static void configureJob(
            MemorySegment job, Duration timeout, long memoryLimit, int processLimit, Arena arena) throws IOException {
        MemorySegment limits = arena.allocate(144, 8);
        limits.set(JAVA_LONG, 0, Math.multiplyExact(timeout.toMillis(), 10_000L));
        int flags = JOB_OBJECT_LIMIT_PROCESS_TIME
                | JOB_OBJECT_LIMIT_ACTIVE_PROCESS
                | JOB_OBJECT_LIMIT_PROCESS_MEMORY
                | JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION
                | JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        limits.set(JAVA_INT, 16, flags);
        limits.set(JAVA_INT, 40, processLimit);
        limits.set(JAVA_LONG, 112, memoryLimit);
        if (callInt(
                        Bindings.SET_JOB_INFORMATION,
                        job,
                        JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                        limits,
                        Math.toIntExact(limits.byteSize()))
                == 0) {
            throw failure("SetInformationJobObject(limits) failed");
        }
        MemorySegment ui = arena.allocate(JAVA_INT);
        ui.set(JAVA_INT, 0, JOB_OBJECT_UILIMIT_ALL);
        if (callInt(
                        Bindings.SET_JOB_INFORMATION,
                        job,
                        JOB_OBJECT_BASIC_UI_RESTRICTIONS,
                        ui,
                        Math.toIntExact(ui.byteSize()))
                == 0) {
            throw failure("SetInformationJobObject(UI restrictions) failed");
        }
    }

    private static void updateAttribute(MemorySegment list, long attribute, MemorySegment value, long size)
            throws IOException {
        if (callInt(Bindings.UPDATE_ATTRIBUTE, list, 0, attribute, value, size, MemorySegment.NULL, MemorySegment.NULL)
                == 0) {
            throw failure("UpdateProcThreadAttribute failed");
        }
    }

    private static AclSnapshot changeAcl(Path path, MemorySegment sid, int permissions, int accessMode)
            throws IOException {
        rejectReparsePoint(path);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment daclOut = arena.allocate(ADDRESS);
            MemorySegment descriptorOut = arena.allocate(ADDRESS);
            int status = callInt(
                    Bindings.GET_NAMED_SECURITY_INFO,
                    wide(arena, path.toString()),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    daclOut,
                    MemorySegment.NULL,
                    descriptorOut);
            if (status != 0) {
                throw windowsStatus("GetNamedSecurityInfoW failed", status);
            }
            MemorySegment descriptor = descriptorOut.get(ADDRESS, 0);
            try {
                boolean protectedDacl = daclProtected(descriptor, arena);
                String original = descriptorSddl(descriptor, arena);
                MemorySegment entry = arena.allocate(EXPLICIT_ACCESS);
                entry.set(JAVA_INT, 0, permissions);
                entry.set(JAVA_INT, 4, accessMode);
                entry.set(JAVA_INT, 8, Files.isDirectory(path) ? SUB_CONTAINERS_AND_OBJECTS_INHERIT : 0);
                entry.set(ADDRESS, 16, MemorySegment.NULL);
                entry.set(JAVA_INT, 24, 0);
                entry.set(JAVA_INT, 28, TRUSTEE_IS_SID);
                entry.set(JAVA_INT, 32, TRUSTEE_IS_WELL_KNOWN_GROUP);
                entry.set(ADDRESS, 40, sid);
                MemorySegment aclOut = arena.allocate(ADDRESS);
                status = callInt(Bindings.SET_ENTRIES_IN_ACL, 1, entry, daclOut.get(ADDRESS, 0), aclOut);
                if (status != 0) {
                    throw windowsStatus("SetEntriesInAclW failed", status);
                }
                MemorySegment updatedAcl = aclOut.get(ADDRESS, 0);
                try {
                    status = callInt(
                            Bindings.SET_NAMED_SECURITY_INFO,
                            wide(arena, path.toString()),
                            SE_FILE_OBJECT,
                            daclFlags(protectedDacl),
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            updatedAcl,
                            MemorySegment.NULL);
                    if (status != 0) {
                        throw windowsStatus("SetNamedSecurityInfoW failed", status);
                    }
                } finally {
                    callAddress(Bindings.LOCAL_FREE, updatedAcl);
                }
                return new AclSnapshot(path, original, protectedDacl);
            } finally {
                callAddress(Bindings.LOCAL_FREE, descriptor);
            }
        }
    }

    private static IOException restoreAcls(List<AclSnapshot> snapshots) {
        IOException failure = null;
        for (int index = snapshots.size() - 1; index >= 0; index--) {
            try {
                restoreAcl(snapshots.get(index));
            } catch (IOException current) {
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
        }
        return failure;
    }

    private static void restoreAcl(AclSnapshot snapshot) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment descriptorOut = arena.allocate(ADDRESS);
            if (callInt(
                            Bindings.CONVERT_SDDL,
                            wide(arena, snapshot.sddl()),
                            SDDL_REVISION_1,
                            descriptorOut,
                            MemorySegment.NULL)
                    == 0) {
                throw failure("cannot decode saved Workspace DACL");
            }
            MemorySegment descriptor = descriptorOut.get(ADDRESS, 0);
            try {
                MemorySegment present = arena.allocate(JAVA_INT);
                MemorySegment daclOut = arena.allocate(ADDRESS);
                MemorySegment defaulted = arena.allocate(JAVA_INT);
                if (callInt(Bindings.GET_SECURITY_DESCRIPTOR_DACL, descriptor, present, daclOut, defaulted) == 0
                        || present.get(JAVA_INT, 0) == 0) {
                    throw failure("saved Workspace security descriptor has no DACL");
                }
                int status = callInt(
                        Bindings.SET_NAMED_SECURITY_INFO,
                        wide(arena, snapshot.path().toString()),
                        SE_FILE_OBJECT,
                        daclFlags(snapshot.protectedDacl()),
                        MemorySegment.NULL,
                        MemorySegment.NULL,
                        daclOut.get(ADDRESS, 0),
                        MemorySegment.NULL);
                if (status != 0) {
                    throw windowsStatus("cannot restore Workspace DACL", status);
                }
            } finally {
                callAddress(Bindings.LOCAL_FREE, descriptor);
            }
        }
    }

    private static boolean daclProtected(MemorySegment descriptor, Arena arena) throws IOException {
        MemorySegment control = arena.allocate(JAVA_SHORT);
        MemorySegment revision = arena.allocate(JAVA_INT);
        if (callInt(Bindings.GET_SECURITY_DESCRIPTOR_CONTROL, descriptor, control, revision) == 0) {
            throw failure("GetSecurityDescriptorControl failed");
        }
        return (Short.toUnsignedInt(control.get(JAVA_SHORT, 0)) & SE_DACL_PROTECTED) != 0;
    }

    private static String descriptorSddl(MemorySegment descriptor, Arena arena) throws IOException {
        MemorySegment textOut = arena.allocate(ADDRESS);
        if (callInt(
                        Bindings.CONVERT_DESCRIPTOR_TO_SDDL,
                        descriptor,
                        SDDL_REVISION_1,
                        DACL_SECURITY_INFORMATION,
                        textOut,
                        MemorySegment.NULL)
                == 0) {
            throw failure("ConvertSecurityDescriptorToStringSecurityDescriptorW failed");
        }
        MemorySegment text = textOut.get(ADDRESS, 0);
        try {
            return readWide(text);
        } finally {
            callAddress(Bindings.LOCAL_FREE, text);
        }
    }

    private static int daclFlags(boolean protectedDacl) {
        return DACL_SECURITY_INFORMATION
                | (protectedDacl ? PROTECTED_DACL_SECURITY_INFORMATION : UNPROTECTED_DACL_SECURITY_INFORMATION);
    }

    private static Path canonicalExisting(Path value, String name) throws IOException {
        Path path = Objects.requireNonNull(value, name).toAbsolutePath().normalize();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(name + " does not exist: " + path);
        }
        rejectReparsePoint(path);
        Path real = path.toRealPath();
        rejectReparsePoint(real);
        return real;
    }

    private static void rejectReparsePoint(Path path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            int attributes = callInt(Bindings.GET_FILE_ATTRIBUTES, wide(arena, path.toString()));
            if (attributes == INVALID_FILE_ATTRIBUTES) {
                throw failure("GetFileAttributesW failed for " + path);
            }
            if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                throw new IOException("Windows sandbox root is a reparse point: " + path);
            }
        }
    }

    private static String commandLine(List<String> arguments) {
        return arguments.stream().map(WindowsSandbox::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    private static String quote(String value) {
        if (!value.isEmpty()
                && value.chars().noneMatch(character -> Character.isWhitespace(character) || character == '"')) {
            return value;
        }
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') {
                slashes++;
                continue;
            }
            if (character == '"') {
                result.append("\\".repeat(slashes * 2 + 1)).append('"');
            } else {
                result.append("\\".repeat(slashes)).append(character);
            }
            slashes = 0;
        }
        result.append("\\".repeat(slashes * 2)).append('"');
        return result.toString();
    }

    private static MemorySegment wide(Arena arena, String value) {
        MemorySegment result = arena.allocate((long) (value.length() + 1) * 2, 2);
        for (int index = 0; index < value.length(); index++) {
            result.set(JAVA_CHAR, (long) index * 2, value.charAt(index));
        }
        result.set(JAVA_CHAR, (long) value.length() * 2, (char) 0);
        return result;
    }

    private static String readWide(MemorySegment address) throws IOException {
        if (address.equals(MemorySegment.NULL)) {
            return "";
        }
        MemorySegment readable = address.reinterpret(1024 * 1024);
        StringBuilder result = new StringBuilder();
        for (long offset = 0; offset < 1024 * 1024; offset += 2) {
            char value = readable.get(JAVA_CHAR, offset);
            if (value == 0) {
                return result.toString();
            }
            result.append(value);
        }
        throw new IOException("Windows native string is unterminated");
    }

    private static void closeHandle(MemorySegment handle) {
        if (handle != null && !handle.equals(MemorySegment.NULL)) {
            callIntUnchecked(Bindings.CLOSE_HANDLE, handle);
        }
    }

    private static void closePseudoConsole(MemorySegment handle) {
        if (handle != null && !handle.equals(MemorySegment.NULL)) {
            callVoidUnchecked(Bindings.CLOSE_PSEUDO_CONSOLE, handle);
        }
    }

    private static MemorySegment globalHandle(MemorySegment handle) {
        return handle == null || handle.equals(MemorySegment.NULL)
                ? MemorySegment.NULL
                : MemorySegment.ofAddress(handle.address());
    }

    private static MemorySegment coord(Arena arena, int columns, int rows) {
        validateConsoleSize(columns, rows);
        MemorySegment value = arena.allocate(COORD);
        value.set(JAVA_SHORT, 0, (short) columns);
        value.set(JAVA_SHORT, 2, (short) rows);
        return value;
    }

    private static void validateConsoleSize(int columns, int rows) {
        if (columns < 20 || columns > 1_000 || rows < 5 || rows > 1_000) {
            throw new IllegalArgumentException("ConPTY dimensions are invalid");
        }
    }

    private static void requireHandle(MemorySegment handle, String operation) throws IOException {
        if (handle == null || handle.equals(MemorySegment.NULL) || handle.address() == -1L) {
            throw failure(operation + " failed");
        }
    }

    private static IOException failure(String message) {
        return new IOException(message + " (Windows error " + lastError() + ")");
    }

    private static IOException windowsStatus(String message, int status) {
        return new IOException(message + " (Windows status " + Integer.toUnsignedString(status) + ")");
    }

    private static int lastError() {
        try {
            return (int) Bindings.GET_LAST_ERROR.invokeExact();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int callInt(MethodHandle handle, Object... arguments) throws IOException {
        try {
            return (int) handle.invokeWithArguments(arguments);
        } catch (Throwable failure) {
            throw asIo("Windows FFM call failed", failure);
        }
    }

    private static int callIntUnchecked(MethodHandle handle, Object... arguments) {
        try {
            return (int) handle.invokeWithArguments(arguments);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static MemorySegment callAddress(MethodHandle handle, Object... arguments) throws IOException {
        try {
            return (MemorySegment) handle.invokeWithArguments(arguments);
        } catch (Throwable failure) {
            throw asIo("Windows FFM call failed", failure);
        }
    }

    private static void callVoidUnchecked(MethodHandle handle, Object... arguments) {
        try {
            handle.invokeWithArguments(arguments);
        } catch (Throwable ignored) {
        }
    }

    private static IOException asIo(String message, Throwable failure) {
        return failure instanceof IOException io ? io : new IOException(message, failure);
    }

    private static void requireSupported() {
        if (!isSupported()) {
            throw new UnsupportedOperationException("Windows AppContainer sandbox requires 64-bit Windows 10 or later");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    /** Live Windows pseudo-console. All handles remain owned by the Sandbox Launcher process. */
    public static final class PseudoConsoleSession implements AutoCloseable {
        private final MemorySegment pseudoConsole;
        private final MemorySegment input;
        private final MemorySegment output;
        private final MemorySegment process;
        private final MemorySegment job;
        private final PreparedScope scope;
        private final AtomicBoolean inputClosed = new AtomicBoolean();
        private final AtomicBoolean pseudoClosed = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private PseudoConsoleSession(
                MemorySegment pseudoConsole,
                MemorySegment input,
                MemorySegment output,
                MemorySegment process,
                MemorySegment job,
                PreparedScope scope) {
            this.pseudoConsole = pseudoConsole;
            this.input = input;
            this.output = output;
            this.process = process;
            this.job = job;
            this.scope = scope;
        }

        /** Returns null after the ConPTY output pipe reaches EOF. */
        public byte[] read(int maximumBytes) throws IOException {
            if (maximumBytes < 1 || maximumBytes > 1024 * 1024) {
                throw new IllegalArgumentException("ConPTY read bound is invalid");
            }
            if (closed.get()) {
                return null;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(maximumBytes);
                MemorySegment count = arena.allocate(JAVA_INT);
                int read = callInt(Bindings.READ_FILE, output, buffer, maximumBytes, count, MemorySegment.NULL);
                if (read == 0) {
                    int error = lastError();
                    if (error == ERROR_BROKEN_PIPE || error == ERROR_NO_DATA || closed.get()) {
                        return null;
                    }
                    throw new IOException("ReadFile(ConPTY) failed (Windows error " + error + ")");
                }
                int bytes = count.get(JAVA_INT, 0);
                if (bytes <= 0) {
                    return null;
                }
                if (bytes > maximumBytes) {
                    throw new IOException("ReadFile returned an invalid ConPTY byte count");
                }
                return buffer.asSlice(0, bytes).toArray(JAVA_BYTE);
            }
        }

        /** 串行写完 ConPTY 输入；value 非空且最多 1 MiB，空数组忽略，输入关闭或原生写入失败时抛出 IOException。 */
        public synchronized void write(byte[] value) throws IOException {
            Objects.requireNonNull(value, "value");
            if (value.length == 0) {
                return;
            }
            if (value.length > 1024 * 1024) {
                throw new IllegalArgumentException("ConPTY write bound is invalid");
            }
            if (closed.get() || inputClosed.get()) {
                throw new IOException("ConPTY input is closed");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocateFrom(JAVA_BYTE, value);
                int offset = 0;
                while (offset < value.length) {
                    MemorySegment count = arena.allocate(JAVA_INT);
                    if (callInt(
                                    Bindings.WRITE_FILE,
                                    input,
                                    buffer.asSlice(offset),
                                    value.length - offset,
                                    count,
                                    MemorySegment.NULL)
                            == 0) {
                        throw failure("WriteFile(ConPTY) failed");
                    }
                    int written = count.get(JAVA_INT, 0);
                    if (written < 1 || written > value.length - offset) {
                        throw new IOException("WriteFile returned an invalid ConPTY byte count");
                    }
                    offset += written;
                }
            }
        }

        /** 幂等关闭 ConPTY 输入句柄，保留输出读取端；与 write 共用监视器，避免写入过程中关闭句柄。 */
        public synchronized void closeInput() {
            if (inputClosed.compareAndSet(false, true)) {
                closeHandle(input);
            }
        }

        /** 串行调整 ConPTY 字符尺寸；尺寸由 coord 校验，终端已关闭或原生调整失败时抛出 IOException。 */
        public synchronized void resize(int columns, int rows) throws IOException {
            if (closed.get() || pseudoClosed.get()) {
                throw new IOException("ConPTY is closed");
            }
            try (Arena arena = Arena.ofConfined()) {
                int status = callInt(Bindings.RESIZE_PSEUDO_CONSOLE, pseudoConsole, coord(arena, columns, rows));
                if (status != 0) {
                    throw new IOException("ResizePseudoConsole failed (HRESULT 0x" + Integer.toHexString(status) + ")");
                }
            }
        }

        /** ConPTY converts the VT ETX byte to a console Ctrl+C input event. */
        public void interrupt() throws IOException {
            write(new byte[] {3});
        }

        /** 请求 Job 以 exitCode 终止整棵受控进程树；已关闭时不操作，不负责等待退出或释放会话资源。 */
        public void terminate(int exitCode) {
            if (!closed.get()) {
                callIntUnchecked(Bindings.TERMINATE_JOB, job, exitCode);
            }
        }

        /** 无等待地查询目标进程状态；仍运行返回 true，已退出返回 false，无法查询时抛出 IOException。 */
        public boolean isAlive() throws IOException {
            int wait = callInt(Bindings.WAIT_FOR_SINGLE_OBJECT, process, 0);
            if (wait == WAIT_TIMEOUT) {
                return true;
            }
            if (wait == WAIT_OBJECT_0) {
                return false;
            }
            throw failure("WaitForSingleObject failed");
        }

        /** Returns null when the bounded wait expires. */
        public Integer awaitExit(Duration timeout) throws IOException {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout is negative");
            }
            long millis = Math.min(0xfffffff0L, timeout.toMillis());
            int wait = callInt(Bindings.WAIT_FOR_SINGLE_OBJECT, process, (int) millis);
            if (wait == WAIT_TIMEOUT) {
                return null;
            }
            if (wait != WAIT_OBJECT_0) {
                throw failure("WaitForSingleObject failed");
            }
            MemorySegment exitCode;
            try (Arena arena = Arena.ofConfined()) {
                exitCode = arena.allocate(JAVA_INT);
                if (callInt(Bindings.GET_EXIT_CODE_PROCESS, process, exitCode) == 0) {
                    throw failure("GetExitCodeProcess failed");
                }
                return exitCode.get(JAVA_INT, 0);
            }
        }

        /** Closes the ConPTY server ends so a reader can drain buffered output and observe EOF. */
        public void finishOutput() {
            if (pseudoClosed.compareAndSet(false, true)) {
                closePseudoConsole(pseudoConsole);
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            // 先终止 Job 中的目标，再释放 ConPTY/HANDLE，最后恢复临时 ACL；恢复失败必须由上层锁定 Workspace。
            try {
                try {
                    if (callInt(Bindings.WAIT_FOR_SINGLE_OBJECT, process, 0) == WAIT_TIMEOUT) {
                        callIntUnchecked(Bindings.TERMINATE_JOB, job, 137);
                        callIntUnchecked(Bindings.WAIT_FOR_SINGLE_OBJECT, process, 5_000);
                    }
                } finally {
                    finishOutput();
                    closeInput();
                    closeHandle(output);
                    closeHandle(process);
                    closeHandle(job);
                }
            } finally {
                scope.close();
            }
        }
    }

    private static final class PreparedScope implements AutoCloseable {
        private final String profileName;
        private final MemorySegment profileSid;
        private final List<AclSnapshot> aclSnapshots;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PreparedScope(String profileName, MemorySegment profileSid, List<AclSnapshot> aclSnapshots) {
            this.profileName = profileName;
            this.profileSid = profileSid;
            this.aclSnapshots = List.copyOf(aclSnapshots);
        }

        private MemorySegment profileSid() {
            return profileSid;
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            IOException cleanupFailure = restoreAcls(aclSnapshots);
            try {
                callAddress(Bindings.FREE_SID, profileSid);
            } catch (IOException failure) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure;
                } else {
                    cleanupFailure.addSuppressed(failure);
                }
            }
            try (Arena arena = Arena.ofConfined()) {
                int deleted = callInt(Bindings.DELETE_APP_CONTAINER_PROFILE, wide(arena, profileName));
                if (deleted != 0) {
                    IOException failure = new IOException(
                            "DeleteAppContainerProfile failed (HRESULT 0x" + Integer.toHexString(deleted) + ")");
                    if (cleanupFailure == null) {
                        cleanupFailure = failure;
                    } else {
                        cleanupFailure.addSuppressed(failure);
                    }
                }
            } catch (Throwable failure) {
                IOException value = asIo("cannot delete AppContainer profile", failure);
                if (cleanupFailure == null) {
                    cleanupFailure = value;
                } else {
                    cleanupFailure.addSuppressed(value);
                }
            }
            if (cleanupFailure != null) {
                throw new AclRestorationException(
                        "Windows sandbox ACL restoration failed; lock the Workspace", cleanupFailure);
            }
        }
    }

    /** 临时权限或 AppContainer 清理失败的信号；上层必须锁定对应 Workspace，不能继续按已恢复权限处理。 */
    public static final class AclRestorationException extends IOException {
        /** 保留清理失败说明和根因；message 用于诊断，不应包含凭据，cause 保存原生恢复失败。 */
        public AclRestorationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record AclSnapshot(Path path, String sddl, boolean protectedDacl) {}

    private static final class Bindings {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32", Arena.global());
        private static final SymbolLookup ADVAPI = SymbolLookup.libraryLookup("advapi32", Arena.global());
        private static final SymbolLookup USERENV = SymbolLookup.libraryLookup("userenv", Arena.global());

        private static final MethodHandle GET_LAST_ERROR =
                function(KERNEL, "GetLastError", FunctionDescriptor.of(JAVA_INT));
        private static final MethodHandle GET_CURRENT_PROCESS =
                function(KERNEL, "GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
        private static final MethodHandle OPEN_PROCESS_TOKEN =
                function(ADVAPI, "OpenProcessToken", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        private static final MethodHandle CREATE_RESTRICTED_TOKEN = function(
                ADVAPI,
                "CreateRestrictedToken",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle SET_TOKEN_INFORMATION = function(
                ADVAPI, "SetTokenInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle CONVERT_STRING_SID =
                function(ADVAPI, "ConvertStringSidToSidW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle GET_LENGTH_SID =
                function(ADVAPI, "GetLengthSid", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle CREATE_APP_CONTAINER_PROFILE = function(
                USERENV,
                "CreateAppContainerProfile",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        private static final MethodHandle DELETE_APP_CONTAINER_PROFILE =
                function(USERENV, "DeleteAppContainerProfile", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle FREE_SID =
                function(ADVAPI, "FreeSid", FunctionDescriptor.of(ADDRESS, ADDRESS));

        private static final MethodHandle GET_NAMED_SECURITY_INFO = function(
                ADVAPI,
                "GetNamedSecurityInfoW",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle SET_NAMED_SECURITY_INFO = function(
                ADVAPI,
                "SetNamedSecurityInfoW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle SET_ENTRIES_IN_ACL = function(
                ADVAPI, "SetEntriesInAclW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle CONVERT_DESCRIPTOR_TO_SDDL = function(
                ADVAPI,
                "ConvertSecurityDescriptorToStringSecurityDescriptorW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle CONVERT_SDDL = function(
                ADVAPI,
                "ConvertStringSecurityDescriptorToSecurityDescriptorW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle GET_SECURITY_DESCRIPTOR_DACL = function(
                ADVAPI,
                "GetSecurityDescriptorDacl",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle GET_SECURITY_DESCRIPTOR_CONTROL = function(
                ADVAPI, "GetSecurityDescriptorControl", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

        private static final MethodHandle CREATE_JOB_OBJECT =
                function(KERNEL, "CreateJobObjectW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle SET_JOB_INFORMATION = function(
                KERNEL,
                "SetInformationJobObject",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle ASSIGN_PROCESS_TO_JOB =
                function(KERNEL, "AssignProcessToJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle TERMINATE_JOB =
                function(KERNEL, "TerminateJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle GET_STD_HANDLE =
                function(KERNEL, "GetStdHandle", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        private static final MethodHandle SET_HANDLE_INFORMATION =
                function(KERNEL, "SetHandleInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        private static final MethodHandle CREATE_PIPE =
                function(KERNEL, "CreatePipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        private static final MethodHandle READ_FILE = function(
                KERNEL, "ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle WRITE_FILE = function(
                KERNEL, "WriteFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle CREATE_PSEUDO_CONSOLE = function(
                KERNEL,
                "CreatePseudoConsole",
                FunctionDescriptor.of(JAVA_INT, COORD, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        private static final MethodHandle RESIZE_PSEUDO_CONSOLE =
                function(KERNEL, "ResizePseudoConsole", FunctionDescriptor.of(JAVA_INT, ADDRESS, COORD));
        private static final MethodHandle CLOSE_PSEUDO_CONSOLE =
                function(KERNEL, "ClosePseudoConsole", FunctionDescriptor.ofVoid(ADDRESS));
        private static final MethodHandle INITIALIZE_ATTRIBUTE_LIST = function(
                KERNEL,
                "InitializeProcThreadAttributeList",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle UPDATE_ATTRIBUTE = function(
                KERNEL,
                "UpdateProcThreadAttribute",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        private static final MethodHandle DELETE_ATTRIBUTE_LIST =
                function(KERNEL, "DeleteProcThreadAttributeList", FunctionDescriptor.ofVoid(ADDRESS));
        private static final MethodHandle CREATE_PROCESS_AS_USER = function(
                ADVAPI,
                "CreateProcessAsUserW",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS,
                        ADDRESS, ADDRESS));
        private static final MethodHandle RESUME_THREAD =
                function(KERNEL, "ResumeThread", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle WAIT_FOR_SINGLE_OBJECT =
                function(KERNEL, "WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle GET_EXIT_CODE_PROCESS =
                function(KERNEL, "GetExitCodeProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle TERMINATE_PROCESS =
                function(KERNEL, "TerminateProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle GET_FILE_ATTRIBUTES =
                function(KERNEL, "GetFileAttributesW", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle CLOSE_HANDLE =
                function(KERNEL, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle LOCAL_FREE =
                function(KERNEL, "LocalFree", FunctionDescriptor.of(ADDRESS, ADDRESS));

        private static void ensureLoaded() {
            /* class initialization resolves every required API */
        }

        private static MethodHandle function(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
            MemorySegment symbol = lookup.find(name)
                    .orElseThrow(() -> new UnsupportedOperationException("Windows API is unavailable: " + name));
            return LINKER.downcallHandle(symbol, descriptor);
        }
    }
}
