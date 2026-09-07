package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Entry;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Match;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Snapshot;

/** 固定 Worker 的根目录能力；每次读取相对于固定目录句柄打开，禁止将检查过的路径重新交给宿主解析。 */
final class WorkspaceFileTree implements AutoCloseable {
    private final WorkspaceDirectoryAccess access;

    WorkspaceFileTree(Path root) throws IOException {
        if (!root.isAbsolute() || !root.normalize().equals(root)) {
            throw new IOException("Workspace root must be a frozen absolute normalized path");
        }
        access = WorkspaceDirectoryAccess.open(root);
    }

    WorkspaceDirectoryAccess directory(String relative) throws IOException {
        WorkspaceFileProtocol.requireRelative(relative, true);
        return access.directory(relative.equals(".") ? "" : relative);
    }

    WorkspaceDirectoryAccess access() {
        return access;
    }

    Snapshot snapshot(String relative, int maximum) throws IOException {
        WorkspaceFileProtocol.requireRelative(relative, false);
        Path path = Path.of(relative);
        try (var parent = directory(parent(relative))) {
            return snapshot(parent, path.getFileName().toString(), relative, maximum);
        } catch (java.nio.file.NoSuchFileException absent) {
            return new Snapshot(relative, false, "", new byte[0]);
        }
    }

    static Snapshot snapshot(WorkspaceDirectoryAccess parent, String leaf, String relative, int maximum)
            throws IOException {
        BasicFileAttributes before;
        try {
            before = parent.attributes(leaf);
        } catch (java.nio.file.NoSuchFileException absent) {
            return new Snapshot(relative, false, "", new byte[0]);
        }
        if (!before.isRegularFile() || before.size() > maximum) {
            throw new IOException("Workspace file is not regular or exceeds the byte limit: " + relative);
        }
        byte[] bytes = readBounded(parent, leaf, maximum);
        BasicFileAttributes after = parent.attributes(leaf);
        if (!sameIdentity(before, after) || before.size() != bytes.length) {
            throw new IOException("Workspace file changed while reading: " + relative);
        }
        return new Snapshot(relative, true, WorkspaceFileAccess.hash(bytes), bytes);
    }

    private static byte[] readBounded(WorkspaceDirectoryAccess parent, String leaf, int maximum) throws IOException {
        try (SeekableByteChannel channel = parent.openFile(leaf, Set.of(StandardOpenOption.READ));
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int count;
            while ((count = channel.read(buffer)) >= 0) {
                if ((long) output.size() + count > maximum) {
                    throw new IOException("Workspace file grew beyond byte limit");
                }
                output.write(buffer.array(), 0, count);
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    List<Entry> list(String relative, int maximum) throws IOException {
        ArrayList<Entry> result = new ArrayList<>();
        try (var directory = directory(relative)) {
            for (String leaf : directory.names(WorkspaceFileAccess.MAX_ENTRIES)) {
                if ((leaf.equalsIgnoreCase(".git")
                        || leaf.toLowerCase(java.util.Locale.ROOT).startsWith(".javaclaw-recovery-"))) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = directory.attributes(leaf);
                } catch (IOException unavailableOrLink) {
                    continue;
                }
                if (attributes.isSymbolicLink()
                        || attributes.isOther()
                        || !attributes.isDirectory() && !attributes.isRegularFile()) {
                    continue;
                }
                if (result.size() == maximum) {
                    throw new IOException("Workspace directory exceeds entry limit");
                }
                result.add(new Entry(
                        child(relative, leaf),
                        attributes.isDirectory(),
                        attributes.isRegularFile() ? attributes.size() : 0));
            }
        }
        return result.stream().sorted(Comparator.comparing(Entry::path)).toList();
    }

    List<Match> search(String relative, String literal, int maximum, int byteBudget) throws IOException {
        return search(relative, literal, "**", true, maximum, byteBudget);
    }

    List<Match> search(String relative, String literal, String glob, boolean caseSensitive, int maximum, int byteBudget)
            throws IOException {
        WorkspaceSearchPage page = searchPage(relative, literal, glob, caseSensitive, maximum, byteBudget);
        if (page.truncated() && page.scannedBytes() >= byteBudget) {
            throw new IOException("Workspace search exceeds scan byte limit");
        }
        return page.matches();
    }

    WorkspaceSearchPage searchPage(
            String relative, String literal, String glob, boolean caseSensitive, int maximum, int byteBudget)
            throws IOException {
        return WorkspaceFileSearch.search(this, relative, literal, glob, caseSensitive, maximum, byteBudget);
    }

    static String parent(String relative) {
        Path parent = Path.of(relative).getParent();
        return parent == null ? "" : parent.toString().replace('\\', '/');
    }

    static String child(String parent, String leaf) {
        return parent.isEmpty() || parent.equals(".") ? leaf : parent + "/" + leaf;
    }

    static boolean sameIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        return after.isRegularFile()
                && java.util.Objects.equals(before.fileKey(), after.fileKey())
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    @Override
    public void close() throws IOException {
        access.close();
    }
}
