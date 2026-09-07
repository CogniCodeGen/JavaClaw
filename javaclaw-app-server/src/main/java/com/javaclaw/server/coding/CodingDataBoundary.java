package com.javaclaw.server.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import com.javaclaw.api.PermissionProfile;

/** 平台管理目录不属于任何项目权限；项目选择和宽根权限均不能取得管理数据访问权。 */
final class CodingDataBoundary {
    private CodingDataBoundary() {}

    static void requireOutside(Path executionRoot, PermissionProfile permission, Path dataRoot) {
        requireOutside(executionRoot, permission, dataRoot, false);
    }

    static void requireOutside(
            Path executionRoot, PermissionProfile permission, Path dataRoot, boolean managedWorktree) {
        try {
            Path managed = canonical(dataRoot);
            Path execution = canonical(executionRoot);
            boolean isolated = managedWorktree && execution.startsWith(managed) && !execution.equals(managed);
            if (!isolated) {
                requireDisjoint(execution, managed);
            }
            for (List<Path> roots :
                    List.of(permission.files().readRoots(), permission.files().writeRoots())) {
                for (Path root : roots) {
                    Path checked = canonical(root);
                    if (!isolated || !checked.startsWith(execution)) {
                        requireDisjoint(checked, managed);
                    }
                }
            }
        } catch (IOException invalid) {
            throw new SecurityException("CODING_MANAGED_DATA_BOUNDARY: 无法确认平台数据与项目权限隔离", invalid);
        }
    }

    private static void requireDisjoint(Path project, Path managed) {
        if (project.startsWith(managed) || managed.startsWith(project)) {
            throw new SecurityException("CODING_MANAGED_DATA_BOUNDARY: 项目权限与平台管理数据重叠");
        }
    }

    private static Path canonical(Path path) throws IOException {
        Path ancestor = path.toAbsolutePath().normalize();
        ArrayDeque<Path> missing = new ArrayDeque<>();
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(ancestor.getFileName());
            ancestor = ancestor.getParent();
            if (ancestor == null) {
                throw new IOException("路径没有可验证的祖先");
            }
        }
        Path resolved = ancestor.toRealPath();
        for (Path segment : missing) {
            resolved = resolved.resolve(segment);
        }
        return resolved;
    }
}
