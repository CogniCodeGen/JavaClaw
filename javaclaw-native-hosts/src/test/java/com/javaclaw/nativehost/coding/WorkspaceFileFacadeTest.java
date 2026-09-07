package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFileFacadeTest {
    @TempDir
    Path temporary;

    @Test
    void 实际Worker分页与搜索端口传递游标模式和完整摘要预算() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")).toRealPath();
        byte[] content = "Needle\nother\nneedle".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("a.txt"), content);
        Files.writeString(root.resolve("b.java"), "needle");
        var access = new WorkspaceFileAccess(root);
        var token = new CancellationSource();
        assertEquals(2, access.list(".", 2, token).size());
        var first = access.list(".", Optional.empty(), 1, token);
        assertEquals(Optional.of("a.txt"), first.nextName());
        assertEquals(
                "b.java",
                access.list(".", first.nextName(), 1, token)
                        .entries()
                        .getFirst()
                        .path());
        var page = access.read("a.txt", 3, 4, 100, token);
        assertArrayEquals(new byte[] {'d', 'l', 'e', '\n'}, page.content());
        assertEquals(content.length, page.sizeBytes());
        assertEquals(WorkspaceFileAccess.hash(content), page.sha256());
        assertEquals(2, access.search(".", "needle", 10, 100, token).size());
        var insensitive = access.search(".", "needle", "*.txt", false, 10, 100, token);
        assertEquals(2, insensitive.size());
        assertTrue(insensitive.stream().allMatch(match -> match.path().equals("a.txt")));
        var bounded = access.searchPage(".", "needle", "**", false, 1, 100, token);
        assertEquals(1, bounded.matches().size());
        assertTrue(bounded.truncated());
    }

    @Test
    void 有效写权限可用于读取但不能越过删除策略和冻结根() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")).toRealPath();
        Path outside = Files.createDirectory(temporary.resolve("outside")).toRealPath();
        Files.writeString(root.resolve("value"), "before");
        var profile = permission(new FilePermission(List.of(outside), List.of(root), false, false));
        var access = new WorkspaceFileAccess(root, profile);
        var token = new CancellationSource();
        var before = access.read("value", 100, token);
        assertEquals("before", new String(before.content(), StandardCharsets.UTF_8));
        var deletion = new WorkspaceFileAccess.Edit("value", Optional.of(before.sha256()), Optional.empty());
        assertThrows(SecurityException.class, () -> access.preparePatch(List.of(deletion), 100, token));
        var readonly =
                new WorkspaceFileAccess(root, permission(new FilePermission(List.of(root), List.of(), false, false)));
        var update = new WorkspaceFileAccess.Edit("value", Optional.of(before.sha256()), Optional.of(new byte[] {1}));
        assertThrows(SecurityException.class, () -> readonly.preparePatch(List.of(update), 100, token));
        var unavailable = new WorkspaceFileAccess(
                root, permission(new FilePermission(List.of(outside), List.of(), false, false)));
        assertThrows(SecurityException.class, () -> unavailable.stat("value", token));
        var ancestor = new WorkspaceFileAccess(
                root, permission(new FilePermission(List.of(root.getParent()), List.of(), false, false)));
        assertEquals(6, ancestor.stat("value", token).orElseThrow().size());
    }

    @Test
    void 无效根和工具参数在创建子进程前拒绝() throws Exception {
        Path root = temporary.toRealPath();
        Path file = Files.writeString(root.resolve("value"), "text");
        assertThrows(IOException.class, () -> new WorkspaceFileAccess(Path.of("relative")));
        assertThrows(IOException.class, () -> new WorkspaceFileAccess(root.resolve("missing")));
        assertThrows(IOException.class, () -> new WorkspaceFileAccess(file));
        assertThrows(IOException.class, () -> new WorkspaceFileAccess(root.resolve("folder/..")));
        assertThrows(IOException.class, () -> new WorkspaceFileTree(root.resolve("folder/..")));
        assertThrows(IOException.class, () -> new WorkspaceFileTree(Path.of("relative")));
        assertThrows(IOException.class, () -> WorkspaceDirectoryAccess.open(Path.of("relative")));
        assertThrows(IOException.class, () -> WorkspaceDirectoryAccess.open(root.resolve("folder/..")));
        var access = new WorkspaceFileAccess(root);
        var token = new CancellationSource();
        assertThrows(IllegalArgumentException.class, () -> access.read("value", -1, 1, 100, token));
        assertThrows(IllegalArgumentException.class, () -> access.read("value", 0, 0, 100, token));
        assertThrows(IllegalArgumentException.class, () -> access.read("value", 0, 1, 0, token));
        assertThrows(IllegalArgumentException.class, () -> access.list(".", 0, token));
        assertThrows(NullPointerException.class, () -> access.list(".", null, 1, token));
        for (String literal : List.of("", "x".repeat(4097))) {
            assertThrows(
                    IllegalArgumentException.class, () -> access.searchPage(".", literal, "**", true, 1, 10, token));
        }
        assertThrows(IllegalArgumentException.class, () -> access.searchPage(".", null, "**", true, 1, 10, token));
        assertThrows(SecurityException.class, () -> access.read("folder/.JAVACLAW-RECOVERY-owned/value", 100, token));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 权限子根换成兄弟目录链接不能重新授权链接目标() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")).toRealPath();
        Path permitted = Files.createDirectory(root.resolve("permitted"));
        Path sibling = Files.createDirectory(root.resolve("sibling"));
        Files.writeString(sibling.resolve("secret"), "ungranted");
        var access = new WorkspaceFileAccess(
                root, permission(new FilePermission(List.of(permitted), List.of(), false, false)));
        Files.delete(permitted);
        Files.createSymbolicLink(permitted, sibling);
        assertThrows(IOException.class, () -> access.stat("sibling/secret", new CancellationSource()));
        assertThrows(IOException.class, () -> new WorkspaceFileAccess(permitted));
        assertFalse(Files.isRegularFile(permitted));
    }

    private static PermissionProfile permission(FilePermission files) {
        return new PermissionProfile(
                "facade-test",
                1,
                files,
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(5)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, 1024 * 1024, 4, 128));
    }
}
