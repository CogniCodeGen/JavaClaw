package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Match;

/** 搜索遍历始终保留父目录能力；数量、深度和读取字节均有硬上限。 */
final class WorkspaceFileSearch {
    private final PathMatcher matcher;
    private final String query;
    private final boolean caseSensitive;
    private final int maximum;
    private final int byteBudget;
    private final List<Match> matches = new ArrayList<>();
    private long consumed;
    private int visited;
    private boolean truncated;

    private WorkspaceFileSearch(String literal, String glob, boolean caseSensitive, int maximum, int byteBudget) {
        if (glob == null || glob.isEmpty() || glob.length() > 1000) {
            throw new IllegalArgumentException("search glob must contain 1 to 1000 characters");
        }
        matcher = Path.of("").getFileSystem().getPathMatcher("glob:" + glob);
        query = caseSensitive ? literal : literal.toLowerCase(Locale.ROOT);
        this.caseSensitive = caseSensitive;
        this.maximum = maximum;
        this.byteBudget = byteBudget;
    }

    static WorkspaceSearchPage search(
            WorkspaceFileTree tree,
            String path,
            String literal,
            String glob,
            boolean caseSensitive,
            int maximum,
            int byteBudget)
            throws IOException {
        var search = new WorkspaceFileSearch(literal, glob, caseSensitive, maximum, byteBudget);
        try (var directory = tree.directory(path)) {
            search.visit(directory, path.equals(".") ? "" : path, 0);
        }
        return new WorkspaceSearchPage(search.matches, search.truncated, search.consumed);
    }

    private void visit(WorkspaceDirectoryAccess directory, String relative, int depth) throws IOException {
        if (depth > 32) {
            truncated = true;
            return;
        }
        for (String leaf : directory.names(WorkspaceFileAccess.MAX_ENTRIES)) {
            if (stop()) {
                truncated = true;
                return;
            }
            visited++;
            if ((leaf.equalsIgnoreCase(".git")
                    || leaf.toLowerCase(java.util.Locale.ROOT).startsWith(".javaclaw-recovery-"))) {
                continue;
            }
            java.nio.file.attribute.BasicFileAttributes attributes;
            try {
                attributes = directory.attributes(leaf);
            } catch (IOException unavailableOrLink) {
                continue;
            }
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                continue;
            }
            String path = WorkspaceFileTree.child(relative, leaf);
            if (attributes.isDirectory()) {
                try (var child = directory.directory(leaf)) {
                    visit(child, path, depth + 1);
                }
            } else if (attributes.isRegularFile() && matcher.matches(Path.of(path))) {
                read(directory, leaf, path);
            }
        }
    }

    private boolean stop() {
        return matches.size() >= maximum || consumed >= byteBudget || visited >= WorkspaceFileAccess.MAX_ENTRIES;
    }

    private void read(WorkspaceDirectoryAccess directory, String leaf, String relative) throws IOException {
        int remaining = Math.toIntExact(byteBudget - consumed);
        try (var channel = directory.openFile(leaf, Set.of(StandardOpenOption.READ));
                var output = new java.io.ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(Math.min(8192, remaining));
            while (output.size() < remaining) {
                buffer.limit(Math.min(buffer.capacity(), remaining - output.size()));
                int count = channel.read(buffer);
                if (count < 0) {
                    break;
                }
                consumed += count;
                output.write(buffer.array(), 0, count);
                buffer.clear();
            }
            truncated |= channel.position() < channel.size();
            append(relative, output.toByteArray());
        }
    }

    private void append(String path, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0) {
            return;
        }
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length && matches.size() < maximum; index++) {
            String candidate = caseSensitive ? lines[index] : lines[index].toLowerCase(Locale.ROOT);
            if (candidate.contains(query)) {
                String line = lines[index].length() <= 4096 ? lines[index] : lines[index].substring(0, 4096);
                matches.add(new Match(path, index + 1, line));
            }
        }
        truncated |= matches.size() >= maximum;
    }
}
