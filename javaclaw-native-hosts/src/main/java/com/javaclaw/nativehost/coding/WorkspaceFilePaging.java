package com.javaclaw.nativehost.coding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Entry;

/** 保持完整摘要及目录排序的有界分页算法，不把文件偏移当作内存分配大小。 */
final class WorkspaceFilePaging {
    private WorkspaceFilePaging() {}

    static WorkspaceReadPage read(WorkspaceFileTree tree, String relative, long offset, int maximum, int scanLimit)
            throws Exception {
        WorkspaceFileProtocol.limit(maximum, WorkspaceFileAccess.MAX_BYTES, "maximum");
        WorkspaceFileProtocol.limit(scanLimit, WorkspaceFileAccess.MAX_BYTES, "scanLimit");
        if (offset < 0) {
            throw new IllegalArgumentException("file offset must be non-negative");
        }
        WorkspaceFileProtocol.requireRelative(relative, false);
        String leaf = Path.of(relative).getFileName().toString();
        try (var parent = tree.directory(WorkspaceFileTree.parent(relative))) {
            BasicFileAttributes before = parent.attributes(leaf);
            if (!before.isRegularFile() || before.size() > scanLimit) {
                throw new IOException("Workspace file exceeds full digest scan budget or is not regular");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream page = new ByteArrayOutputStream();
            long size;
            try (var channel = parent.openFile(leaf, Set.of(StandardOpenOption.READ))) {
                size = digestPage(channel, digest, page, offset, maximum, scanLimit);
            }
            BasicFileAttributes after = parent.attributes(leaf);
            if (before.size() != size || !WorkspaceFileTree.sameIdentity(before, after)) {
                throw new IOException("Workspace file changed while hashing");
            }
            return new WorkspaceReadPage(
                    relative, size, HexFormat.of().formatHex(digest.digest()), offset, page.toByteArray());
        }
    }

    private static long digestPage(
            SeekableByteChannel channel,
            MessageDigest digest,
            ByteArrayOutputStream page,
            long offset,
            int maximum,
            int scanLimit)
            throws IOException {
        long consumed = 0;
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        int count;
        while ((count = channel.read(buffer)) >= 0) {
            if (consumed + count > scanLimit) {
                throw new IOException("Workspace file grew beyond digest scan budget");
            }
            digest.update(buffer.array(), 0, count);
            if (consumed + count > offset && page.size() < maximum) {
                int first = Math.toIntExact(Math.max(0, offset - consumed));
                int length = Math.min(count - first, maximum - page.size());
                page.write(buffer.array(), first, length);
            }
            consumed += count;
            buffer.clear();
        }
        return consumed;
    }

    static WorkspaceDirectoryPage list(WorkspaceFileTree tree, String path, String afterName, int maximum)
            throws IOException {
        WorkspaceFileProtocol.limit(maximum, WorkspaceFileAccess.MAX_ENTRIES, "maximum");
        List<Entry> candidates = tree.list(path, WorkspaceFileAccess.MAX_ENTRIES).stream()
                .filter(entry -> name(entry).compareTo(afterName) > 0)
                .toList();
        List<Entry> page = candidates.stream().limit(maximum).toList();
        Optional<String> next = candidates.size() > maximum ? Optional.of(name(page.getLast())) : Optional.empty();
        return new WorkspaceDirectoryPage(page, next);
    }

    private static String name(Entry entry) {
        int separator = entry.path().lastIndexOf('/');
        return entry.path().substring(separator + 1);
    }
}
