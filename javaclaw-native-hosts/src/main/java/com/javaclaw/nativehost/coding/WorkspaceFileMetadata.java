package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Entry;

/** 固定根和保留恢复路径的入口检查，以及仅通过目录能力执行的无正文元数据查询。 */
final class WorkspaceFileMetadata {
    private WorkspaceFileMetadata() {}

    static void requireFrozenRoot(Path root) throws IOException {
        if (!root.isAbsolute()
                || !root.normalize().equals(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.toRealPath().equals(root)) {
            throw new IOException("Workspace root must remain the frozen canonical directory");
        }
    }

    static void requireVisible(String relative) {
        for (Path segment : Path.of(relative)) {
            if (segment.toString().toLowerCase(Locale.ROOT).startsWith(".javaclaw-recovery-")) {
                throw new SecurityException("Workspace recovery material is reserved for explicit recovery");
            }
        }
    }

    static Optional<Entry> stat(WorkspaceFileTree tree, String relative) throws IOException {
        WorkspaceFileProtocol.requireRelative(relative, true);
        if (relative.isEmpty() || relative.equals(".")) {
            return Optional.of(new Entry(".", true, 0));
        }
        try (var directory = tree.directory(WorkspaceFileTree.parent(relative))) {
            var attributes =
                    directory.attributes(Path.of(relative).getFileName().toString());
            if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                throw new IOException("Workspace metadata requires a regular file or directory");
            }
            return Optional.of(
                    new Entry(relative, attributes.isDirectory(), attributes.isDirectory() ? 0 : attributes.size()));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
    }
}
