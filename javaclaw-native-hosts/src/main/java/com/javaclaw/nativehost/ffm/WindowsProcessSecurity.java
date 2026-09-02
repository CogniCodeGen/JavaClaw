package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import com.javaclaw.api.ResourceLimits;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** 拥有 Restricted Token 与 kill-on-close Job Object，并在目标 resume 前完成资源约束。 */
final class WindowsProcessSecurity implements AutoCloseable {
    private static final int TOKEN_ASSIGN_PRIMARY = 0x0001;
    private static final int TOKEN_DUPLICATE = 0x0002;
    private static final int TOKEN_QUERY = 0x0008;
    private static final int TOKEN_ADJUST_DEFAULT = 0x0080;
    private static final int TOKEN_ADJUST_SESSIONID = 0x0100;
    private static final int DISABLE_MAX_PRIVILEGE = 0x0001;
    private static final int TOKEN_INTEGRITY_LEVEL = 25;
    private static final int SE_GROUP_INTEGRITY = 0x00000020;
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_BASIC_UI_RESTRICTIONS = 4;
    private static final int JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x00000008;
    private static final int JOB_OBJECT_LIMIT_JOB_MEMORY = 0x00000200;
    private static final int JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION = 0x00000400;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int JOB_OBJECT_UILIMIT_ALL = 0x000000ff;

    private final MemorySegment sourceToken;
    private final MemorySegment restrictedToken;
    private final MemorySegment job;

    private WindowsProcessSecurity(MemorySegment sourceToken, MemorySegment restrictedToken, MemorySegment job) {
        this.sourceToken = sourceToken;
        this.restrictedToken = restrictedToken;
        this.job = job;
    }

    static WindowsProcessSecurity open(ResourceLimits limits, Arena arena) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment source = MemorySegment.NULL;
        MemorySegment restricted = MemorySegment.NULL;
        MemorySegment job = MemorySegment.NULL;
        try {
            MemorySegment sourceOut = arena.allocate(ADDRESS);
            int access = TOKEN_ASSIGN_PRIMARY
                    | TOKEN_DUPLICATE
                    | TOKEN_QUERY
                    | TOKEN_ADJUST_DEFAULT
                    | TOKEN_ADJUST_SESSIONID;
            MemorySegment process = backend.invokePlainAddress(backend.getCurrentProcess);
            var opened = backend.invoke(backend.openProcessToken, process, access, sourceOut);
            if (opened.number() == 0) {
                throw WindowsSandboxNative.error("OpenProcessToken", opened.error());
            }
            source = WindowsSandboxNative.global(sourceOut.get(ADDRESS, 0));
            MemorySegment restrictedOut = arena.allocate(ADDRESS);
            var created = backend.invoke(
                    backend.createRestrictedToken,
                    source,
                    DISABLE_MAX_PRIVILEGE,
                    0,
                    MemorySegment.NULL,
                    0,
                    MemorySegment.NULL,
                    0,
                    MemorySegment.NULL,
                    restrictedOut);
            if (created.number() == 0) {
                throw WindowsSandboxNative.error("CreateRestrictedToken", created.error());
            }
            restricted = WindowsSandboxNative.global(restrictedOut.get(ADDRESS, 0));
            applyLowIntegrity(restricted, arena);
            var createdJob = backend.invoke(backend.createJobObject, MemorySegment.NULL, MemorySegment.NULL);
            job = WindowsSandboxNative.global(createdJob.address());
            WindowsSandboxNative.requireHandle(job, "CreateJobObjectW", createdJob.error());
            configureJob(job, limits, arena);
            return new WindowsProcessSecurity(source, restricted, job);
        } catch (IOException | RuntimeException failure) {
            WindowsSandboxNative.closeHandleQuietly(job);
            WindowsSandboxNative.closeHandleQuietly(restricted);
            WindowsSandboxNative.closeHandleQuietly(source);
            throw failure;
        }
    }

    MemorySegment token() {
        return restrictedToken;
    }

    MemorySegment job() {
        return job;
    }

    void assign(MemorySegment process) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        var result = backend.invoke(backend.assignProcessToJob, job, process);
        if (result.number() == 0) {
            throw WindowsSandboxNative.error("AssignProcessToJobObject", result.error());
        }
    }

    void terminate(int exitCode) {
        try {
            var backend = WindowsSandboxNative.requireBackend();
            backend.invoke(backend.terminateJob, job, exitCode);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        WindowsSandboxNative.closeHandleQuietly(job);
        WindowsSandboxNative.closeHandleQuietly(restrictedToken);
        WindowsSandboxNative.closeHandleQuietly(sourceToken);
    }

    private static void applyLowIntegrity(MemorySegment token, Arena arena) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment sidOut = arena.allocate(ADDRESS);
        var converted =
                backend.invoke(backend.convertStringSid, WindowsSandboxNative.wide(arena, "S-1-16-4096"), sidOut);
        if (converted.number() == 0) {
            throw WindowsSandboxNative.error("ConvertStringSidToSidW", converted.error());
        }
        MemorySegment lowSid = sidOut.get(ADDRESS, 0);
        try {
            MemorySegment label = arena.allocate(WindowsSandboxNative.TOKEN_MANDATORY_LABEL);
            label.set(ADDRESS, 0, lowSid);
            label.set(JAVA_INT, 8, SE_GROUP_INTEGRITY);
            var length = backend.invoke(backend.getLengthSid, lowSid);
            if (length.number() < 1) {
                throw WindowsSandboxNative.error("GetLengthSid", length.error());
            }
            int bytes = Math.toIntExact(WindowsSandboxNative.TOKEN_MANDATORY_LABEL.byteSize()) + length.number();
            var applied = backend.invoke(backend.setTokenInformation, token, TOKEN_INTEGRITY_LEVEL, label, bytes);
            if (applied.number() == 0) {
                throw WindowsSandboxNative.error("SetTokenInformation", applied.error());
            }
        } finally {
            WindowsSandboxNative.localFree(lowSid);
        }
    }

    private static void configureJob(MemorySegment job, ResourceLimits limits, Arena arena) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment information = arena.allocate(144, 8);
        int flags = JOB_OBJECT_LIMIT_ACTIVE_PROCESS
                | JOB_OBJECT_LIMIT_JOB_MEMORY
                | JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION
                | JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        information.set(JAVA_INT, 16, flags);
        information.set(JAVA_INT, 40, Math.addExact(limits.childProcesses(), 1));
        information.set(JAVA_LONG, 120, limits.memoryBytes());
        var configured = backend.invoke(
                backend.setJobInformation,
                job,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                information,
                Math.toIntExact(information.byteSize()));
        if (configured.number() == 0) {
            throw WindowsSandboxNative.error("SetInformationJobObject(limits)", configured.error());
        }
        MemorySegment ui = arena.allocate(JAVA_INT);
        ui.set(JAVA_INT, 0, JOB_OBJECT_UILIMIT_ALL);
        var restricted = backend.invoke(
                backend.setJobInformation, job, JOB_OBJECT_BASIC_UI_RESTRICTIONS, ui, Math.toIntExact(ui.byteSize()));
        if (restricted.number() == 0) {
            throw WindowsSandboxNative.error("SetInformationJobObject(UI)", restricted.error());
        }
    }
}
