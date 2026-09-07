package com.javaclaw.nativehost.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceFileAccessIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void nativeWorkerReadsPreparesAppliesAndRestoresWithoutShell() throws Exception {
        Files.writeString(temporary.resolve("a.txt"), "before");
        var access = new WorkspaceFileAccess(temporary.toRealPath());
        var cancellation = new CancellationSource();
        var before = access.read("a.txt", 1000, cancellation);
        assertEquals(Optional.of(new WorkspaceFileAccess.Entry("a.txt", false, 6)), access.stat("a.txt", cancellation));
        assertEquals(Optional.of(new WorkspaceFileAccess.Entry(".", true, 0)), access.stat(".", cancellation));
        assertEquals(Optional.empty(), access.stat(".mvn/maven.config", cancellation));
        assertThrows(SecurityException.class, () -> access.stat(".javaclaw-recovery-owned/value", cancellation));
        assertFalse(access.read(".mvn/maven.config", 1000, cancellation).exists());
        assertEquals(
                List.of(new WorkspaceFileAccess.Entry("a.txt", false, 6)),
                access.list(".", Optional.empty(), 10, cancellation).entries());
        var inventory = access.inventory(".", 10, 1000, List.of(".git"), cancellation);
        assertFalse(inventory.truncated());
        assertEquals(6, inventory.scannedBytes());
        assertEquals(before.sha256(), inventory.entries().getFirst().sha256());
        var patch = access.preparePatch(
                List.of(new WorkspaceFileAccess.Edit(
                        "a.txt", Optional.of(before.sha256()), Optional.of("after".getBytes(StandardCharsets.UTF_8)))),
                1000,
                cancellation);
        var applied = access.applyPrepared(patch, cancellation);
        assertEquals(WorkspaceFileAccess.Status.APPLIED, applied.status(), applied.detail());
        assertEquals("after", Files.readString(temporary.resolve("a.txt")));
        assertEquals(
                WorkspaceFileAccess.Status.APPLIED,
                access.restore(patch, cancellation).status());
        assertEquals("before", Files.readString(temporary.resolve("a.txt")));
    }

    @Test
    void nativeWorkerCannotReadSiblingBeyondEffectiveReadRoot() throws Exception {
        Path permitted = Files.createDirectory(temporary.resolve("permitted")).toRealPath();
        Files.writeString(permitted.resolve("inside"), "allowed");
        Files.writeString(temporary.resolve("outside"), "denied");
        var permission = new PermissionProfile(
                "scoped-file",
                1,
                new FilePermission(List.of(permitted), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, 1024 * 1024, 4, 128));
        var access = new WorkspaceFileAccess(temporary.toRealPath(), permission);
        var token = new CancellationSource();
        assertEquals(
                "allowed",
                new String(access.read("permitted/inside", 1000, token).content(), StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> access.read("outside", 1000, token));
        assertThrows(SecurityException.class, () -> access.stat("outside", token));
    }
}
