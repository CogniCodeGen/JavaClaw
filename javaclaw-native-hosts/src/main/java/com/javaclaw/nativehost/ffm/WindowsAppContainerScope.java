package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.ADDRESS;

/** 拥有一次执行所需的唯一 AppContainer profile、SID 和临时 ACL。 */
final class WindowsAppContainerScope implements AutoCloseable {
    private final String profileName;
    private final MemorySegment sid;
    private final List<WindowsAclManager.Snapshot> snapshots;
    private final AtomicBoolean closed = new AtomicBoolean();

    private WindowsAppContainerScope(
            String profileName, MemorySegment sid, List<WindowsAclManager.Snapshot> snapshots) {
        this.profileName = profileName;
        this.sid = sid;
        this.snapshots = List.copyOf(snapshots);
    }

    static WindowsAppContainerScope open(WindowsSandboxPaths.Prepared request) throws IOException {
        String name = "JavaClaw.Sandbox.v6." + UUID.randomUUID().toString().replace("-", "");
        MemorySegment sid = createProfile(name);
        try {
            return new WindowsAppContainerScope(name, sid, WindowsAclManager.grant(request, sid));
        } catch (IOException | RuntimeException failure) {
            WindowsAppContainerScope partial = new WindowsAppContainerScope(name, sid, List.of());
            try {
                partial.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    String profileName() {
        return profileName;
    }

    MemorySegment sid() {
        return sid;
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException cleanup = WindowsAclManager.restore(snapshots);
        WindowsSandboxNative.freeSid(sid);
        try {
            deleteProfile();
        } catch (IOException failure) {
            if (cleanup == null) {
                cleanup = failure;
            } else {
                cleanup.addSuppressed(failure);
            }
        }
        if (cleanup != null) {
            throw new WindowsSandbox.AclRestorationException(
                    "Windows Sandbox ACL/profile restoration failed; lock the Workspace", cleanup);
        }
    }

    private static MemorySegment createProfile(String name) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sidOut = arena.allocate(ADDRESS);
            var result = backend.invoke(
                    backend.createAppContainerProfile,
                    WindowsSandboxNative.wide(arena, name),
                    WindowsSandboxNative.wide(arena, "JavaClaw Sandbox"),
                    WindowsSandboxNative.wide(arena, "Ephemeral JavaClaw 6 sandbox"),
                    MemorySegment.NULL,
                    0,
                    sidOut);
            if (result.number() != 0) {
                throw WindowsSandboxNative.status("CreateAppContainerProfile", result.number());
            }
            MemorySegment created = sidOut.get(ADDRESS, 0);
            WindowsSandboxNative.requireHandle(created, "CreateAppContainerProfile SID", result.error());
            return WindowsSandboxNative.global(created);
        }
    }

    private void deleteProfile() throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            var result =
                    backend.invoke(backend.deleteAppContainerProfile, WindowsSandboxNative.wide(arena, profileName));
            if (result.number() != 0) {
                throw WindowsSandboxNative.status("DeleteAppContainerProfile", result.number());
            }
        }
    }
}
