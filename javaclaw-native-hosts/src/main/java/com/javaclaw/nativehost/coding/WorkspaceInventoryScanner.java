package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 固定目录能力内的有界流式摘要；读取期间不重新解析祖先路径。 */
final class WorkspaceInventoryScanner {
    private final int maxEntries;
    private final long maxBytes;
    private final Set<String> excluded;
    private final List<WorkspaceInventory.Entry> entries = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private int visited;
    private long consumed;
    private boolean truncated;

    private WorkspaceInventoryScanner(int maxEntries, int maxBytes, List<String> excluded) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.excluded = Set.copyOf(exclusions(excluded));
    }

    static WorkspaceInventory scan(
            WorkspaceFileTree tree, String path, int maxEntries, int maxBytes, List<String> excluded)
            throws IOException {
        WorkspaceFileProtocol.limit(maxEntries, WorkspaceFileAccess.MAX_ENTRIES, "maxEntries");
        WorkspaceFileProtocol.limit(maxBytes, WorkspaceFileAccess.MAX_BYTES, "maxBytes");
        var scanner = new WorkspaceInventoryScanner(maxEntries, maxBytes, excluded);
        try (var directory = tree.directory(path)) {
            scanner.visit(directory, path.equals(".") ? "" : path, 0);
        }
        return new WorkspaceInventory(
                scanner.entries.stream()
                        .sorted(Comparator.comparing(WorkspaceInventory.Entry::path))
                        .toList(),
                scanner.consumed,
                scanner.truncated,
                scanner.skipped.stream().sorted().toList());
    }

    static List<String> exclusions(List<String> requested) {
        Objects.requireNonNull(requested, "excludedDirs");
        if (requested.size() > 101) {
            throw new IllegalArgumentException("inventory supports at most 101 directory exclusions");
        }
        Set<String> names = new HashSet<>();
        names.add(".git");
        for (String name : requested) {
            if (!name.equalsIgnoreCase(".git")) {
                WorkspaceFileProtocol.requireRelative(name, false);
                if (name.contains("/")) {
                    throw new IllegalArgumentException("inventory exclusions must be directory names");
                }
                names.add(name);
            }
        }
        return names.stream().sorted().toList();
    }

    private void visit(WorkspaceDirectoryAccess directory, String relative, int depth) throws IOException {
        if (!admit()) {
            return;
        }
        if (depth > 32) {
            truncated = true;
            return;
        }
        List<String> names;
        try {
            names = directory.names(WorkspaceFileAccess.MAX_ENTRIES);
        } catch (IOException failure) {
            truncated = true;
            return;
        }
        for (String leaf : names) {
            if (visited >= maxEntries) {
                truncated = true;
                break;
            }
            String child = WorkspaceFileTree.child(relative, leaf);
            int beforeVisit = visited;
            try {
                visitEntry(directory, leaf, child, depth);
            } catch (IOException | IllegalArgumentException failure) {
                if (visited == beforeVisit) {
                    visited++;
                }
                truncated = true;
            }
        }
    }

    private void visitEntry(WorkspaceDirectoryAccess directory, String leaf, String relative, int depth)
            throws IOException {
        if (leaf.equalsIgnoreCase(".git")
                || leaf.toLowerCase(java.util.Locale.ROOT).startsWith(".javaclaw-recovery-")) {
            visited++;
            skipped.add(relative);
            return;
        }
        BasicFileAttributes attributes = directory.attributes(leaf);
        if (attributes.isDirectory()) {
            if (excluded.contains(leaf)) {
                visited++;
                skipped.add(relative);
                return;
            }
            try (var child = directory.directory(leaf)) {
                visit(child, relative, depth + 1);
            }
        } else if (admit()) {
            if (!attributes.isRegularFile() || attributes.size() > maxBytes - consumed) {
                truncated = true;
            } else {
                hash(directory, leaf, relative, attributes);
            }
        }
    }

    private boolean admit() {
        if (visited >= maxEntries) {
            truncated = true;
            return false;
        }
        visited++;
        return true;
    }

    private void hash(WorkspaceDirectoryAccess directory, String leaf, String relative, BasicFileAttributes before)
            throws IOException {
        MessageDigest digest = sha256();
        long size = 0;
        try (SeekableByteChannel input = directory.openFile(leaf, Set.of(StandardOpenOption.READ))) {
            ByteBuffer buffer = ByteBuffer.allocate(32 * 1024);
            while (size < before.size()) {
                buffer.limit((int) Math.min(buffer.capacity(), before.size() - size));
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                consumed += count;
                size += count;
                digest.update(buffer.array(), 0, count);
                buffer.clear();
            }
        }
        BasicFileAttributes after = directory.attributes(leaf);
        if (!after.isRegularFile()
                || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size()
                || size != before.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw new IOException("Workspace inventory file changed during scan");
        }
        entries.add(new WorkspaceInventory.Entry(relative, size, HexFormat.of().formatHex(digest.digest())));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK must provide SHA-256", failure);
        }
    }
}
