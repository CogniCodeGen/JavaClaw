package com.javaclaw.nativehost.coding;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Change;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Edit;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Entry;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Match;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.PreparedPatch;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Snapshot;

/** 固定文件 Worker 的有界二进制协议，不使用 Java 对象反序列化。 */
final class WorkspaceFileProtocol {
    private WorkspaceFileProtocol() {}

    static void requireRelative(String value, boolean allowRoot) {
        if (invalidPathText(value)) {
            throw new IllegalArgumentException("file path must be a bounded relative path using / separators");
        }
        if ((value.isEmpty() || value.equals(".")) && allowRoot) {
            return;
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("file path cannot be empty");
        }
        for (String segment : value.split("/", -1)) {
            if (invalidSegment(segment)) {
                throw new IllegalArgumentException("file path contains a forbidden segment");
            }
        }
        if (Path.of(value).isAbsolute()) {
            throw new IllegalArgumentException("file path must be relative");
        }
    }

    private static boolean invalidPathText(String value) {
        return value == null
                || value.length() > 4096
                || value.indexOf('\0') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0
                || value.startsWith("/");
    }

    private static boolean invalidSegment(String segment) {
        return segment.isEmpty()
                || segment.equals(".")
                || segment.equals("..")
                || segment.equalsIgnoreCase(".git")
                || segment.endsWith(".")
                || segment.endsWith(" ")
                || windowsDevice(segment);
    }

    private static boolean windowsDevice(String segment) {
        String name = segment.split("\\.", 2)[0].toUpperCase(java.util.Locale.ROOT);
        return name.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]");
    }

    static void limit(int value, int maximum, String name) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
    }

    static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > WorkspaceFileAccess.MAX_BYTES) {
            throw new IOException("invalid file protocol byte count");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated file protocol content");
        }
        return bytes;
    }

    static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static Snapshot readSnapshot(DataInputStream input) throws IOException {
        return new Snapshot(input.readUTF(), input.readBoolean(), input.readUTF(), readBytes(input));
    }

    static void writeSnapshot(DataOutputStream output, Snapshot snapshot) throws IOException {
        output.writeUTF(snapshot.path());
        output.writeBoolean(snapshot.exists());
        output.writeUTF(snapshot.sha256());
        writeBytes(output, snapshot.content());
    }

    static List<Edit> readEdits(DataInputStream input) throws IOException {
        int count = count(input, 200);
        ArrayList<Edit> edits = new ArrayList<>();
        long bytes = 0;
        for (int index = 0; index < count; index++) {
            String path = input.readUTF();
            Optional<String> expected = input.readBoolean() ? Optional.of(input.readUTF()) : Optional.empty();
            Optional<byte[]> content = input.readBoolean() ? Optional.of(readBytes(input)) : Optional.empty();
            bytes += content.map(value -> value.length).orElse(0);
            if (bytes > WorkspaceFileAccess.MAX_BYTES) {
                throw new IOException("file edits exceed cumulative byte limit");
            }
            edits.add(new Edit(path, expected, content));
        }
        return List.copyOf(edits);
    }

    static void writeEdits(DataOutputStream output, List<Edit> edits) throws IOException {
        limit(edits.size(), 200, "edits");
        output.writeInt(edits.size());
        for (Edit edit : edits) {
            output.writeUTF(edit.path());
            output.writeBoolean(edit.expectedSha256().isPresent());
            if (edit.expectedSha256().isPresent()) {
                output.writeUTF(edit.expectedSha256().orElseThrow());
            }
            output.writeBoolean(edit.content().isPresent());
            if (edit.content().isPresent()) {
                writeBytes(output, edit.content().orElseThrow());
            }
        }
    }

    static PreparedPatch readPatch(DataInputStream input) throws IOException {
        int count = count(input, 200);
        ArrayList<Change> changes = new ArrayList<>();
        long bytes = 0;
        for (int index = 0; index < count; index++) {
            Snapshot before = readSnapshot(input);
            Snapshot after = readSnapshot(input);
            bytes += before.content().length + (long) after.content().length;
            if (bytes > WorkspaceFileAccess.MAX_BYTES) {
                throw new IOException("patch exceeds cumulative byte limit");
            }
            changes.add(new Change(before, after));
        }
        return new PreparedPatch(changes);
    }

    static void writePatch(DataOutputStream output, PreparedPatch patch) throws IOException {
        output.writeInt(patch.changes().size());
        for (Change change : patch.changes()) {
            writeSnapshot(output, change.before());
            writeSnapshot(output, change.after());
        }
    }

    static List<Entry> readEntries(DataInputStream input) throws IOException {
        int count = count(input, WorkspaceFileAccess.MAX_ENTRIES);
        ArrayList<Entry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(input.readUTF(), input.readBoolean(), input.readLong()));
        }
        return List.copyOf(entries);
    }

    static void writeEntries(DataOutputStream output, List<Entry> entries) throws IOException {
        output.writeInt(entries.size());
        for (Entry entry : entries) {
            output.writeUTF(entry.path());
            output.writeBoolean(entry.directory());
            output.writeLong(entry.size());
        }
    }

    static List<Match> readMatches(DataInputStream input) throws IOException {
        int count = count(input, WorkspaceFileAccess.MAX_ENTRIES);
        ArrayList<Match> matches = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            matches.add(new Match(input.readUTF(), input.readInt(), input.readUTF()));
        }
        return List.copyOf(matches);
    }

    static void writeMatches(DataOutputStream output, List<Match> matches) throws IOException {
        output.writeInt(matches.size());
        for (Match match : matches) {
            output.writeUTF(match.path());
            output.writeInt(match.line());
            output.writeUTF(match.text());
        }
    }

    private static int count(DataInputStream input, int maximum) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("invalid file protocol entry count");
        }
        return count;
    }

    static List<String> readStrings(DataInputStream input, int maximum) throws IOException {
        int size = count(input, maximum);
        List<String> strings = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            String value = input.readUTF();
            if (value.length() > 4096) {
                throw new IOException("inventory path exceeds protocol limit");
            }
            strings.add(value);
        }
        return List.copyOf(strings);
    }

    static void writeStrings(DataOutputStream output, List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) {
            output.writeUTF(value);
        }
    }

    static WorkspaceInventory readInventory(DataInputStream input) throws IOException {
        int size = count(input, WorkspaceFileAccess.MAX_ENTRIES);
        List<WorkspaceInventory.Entry> entries = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            entries.add(new WorkspaceInventory.Entry(input.readUTF(), input.readLong(), input.readUTF()));
        }
        return new WorkspaceInventory(
                entries, input.readLong(), input.readBoolean(), readStrings(input, WorkspaceFileAccess.MAX_ENTRIES));
    }

    static void writeInventory(DataOutputStream output, WorkspaceInventory value) throws IOException {
        output.writeInt(value.entries().size());
        for (WorkspaceInventory.Entry entry : value.entries()) {
            output.writeUTF(entry.path());
            output.writeLong(entry.sizeBytes());
            output.writeUTF(entry.sha256());
        }
        output.writeLong(value.scannedBytes());
        output.writeBoolean(value.truncated());
        writeStrings(output, value.excludedDirectories());
    }
}
