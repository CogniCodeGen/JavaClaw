package com.javaclaw.desktop.ffm.windows;

import com.javaclaw.desktop.api.DesktopApplicationCatalog;
import com.javaclaw.desktop.api.DesktopApplicationInfo;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopApplicationLaunchRejectedException;
import com.javaclaw.desktop.api.DesktopApplicationLaunchUncertainException;
import com.javaclaw.desktop.ffm.DesktopApplicationCatalogPolicy;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import static com.javaclaw.desktop.ffm.windows.Win32.*;

/** Java catalog policy over registry and Shell Link COM; paths never escape into public metadata. */
final class WindowsApplications {
    private static final String APP_PATHS = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths";
    private final Win32 win;
    WindowsApplications(Win32 win) { this.win = win; }

    DesktopApplicationCatalog catalog() {
        Directory directory = directory();
        List<DesktopApplicationInfo> metadata = directory.entries().stream()
                .sorted(Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT)))
                .limit(256).map(Entry::metadata).toList();
        return DesktopApplicationCatalogPolicy.bounded(metadata, directory.truncated() || directory.entries().size() > 256);
    }

    DesktopApplicationLaunch launch(String name) {
        if (!validName(name)) throw rejected(DesktopApplicationLaunchRejectedException.Reason.INVALID_ARGUMENTS,
                "Use an exact installed application name, without a path or arguments");
        String path = registered(name);
        if (path.isEmpty()) {
            List<Entry> matches = directory().entries().stream().filter(entry -> entry.aliases().stream()
                    .anyMatch(alias -> alias.equalsIgnoreCase(name))).toList();
            if (matches.size() > 1) throw rejected(DesktopApplicationLaunchRejectedException.Reason.AMBIGUOUS_APPLICATION,
                    "Multiple installed applications match this exact name");
            if (matches.size() == 1) path = matches.getFirst().path();
        }
        if (!executable(path)) throw rejected(DesktopApplicationLaunchRejectedException.Reason.APPLICATION_NOT_FOUND,
                "Exact installed application did not resolve to a local executable");
        String id = fileName(path).toLowerCase(Locale.ROOT);
        boolean dispatched = false;
        long launchedPid = 0;
        try (Arena arena = Arena.ofConfined()) {
            // SHELLEXECUTEINFOW is 112 bytes on the Windows 64-bit ABI.
            MemorySegment info = arena.allocate(112, 8);
            info.set(I, 0, 112);
            info.set(I, 4, 0x40 | 0x100 | 0x400); // NOCLOSEPROCESS | NOASYNC | FLAG_NO_UI
            info.set(P, 16, wide(arena, "open"));
            info.set(P, 24, wide(arena, path));
            info.set(P, 40, wide(arena, Path.of(path).getParent().toString()));
            info.set(I, 48, 1);
            dispatched = true;
            if (win.integer("shell32", "ShellExecuteExW", new MemoryLayout[]{P}, info) == 0)
                throw new DesktopApplicationLaunchUncertainException("Shell activation was not confirmed; discover windows before retrying", 0, id, null);
            MemorySegment process = info.get(P, 104);
            if (nullPointer(process)) throw new DesktopApplicationLaunchUncertainException("Shell activation returned no process handle; discover windows before retrying", 0, id, null);
            try {
                long pid = Integer.toUnsignedLong(win.integer("kernel32", "GetProcessId", new MemoryLayout[]{P}, process));
                launchedPid = pid;
                if (pid == 0 || win.integer("kernel32", "WaitForSingleObject", new MemoryLayout[]{P, I}, process, 0) != 258)
                    throw new DesktopApplicationLaunchUncertainException("Launcher exited before its window was verified; discover windows before retrying", pid, id, null);
                return new DesktopApplicationLaunch(pid, id, "Windows accepted application activation; discover its window before opening a session");
            } finally { win.integer("kernel32", "CloseHandle", new MemoryLayout[]{P}, process); }
        } catch (DesktopApplicationLaunchUncertainException uncertain) { throw uncertain; }
        catch (RuntimeException | LinkageError failure) {
            if (dispatched) throw new DesktopApplicationLaunchUncertainException(
                    "Windows launch completion is unknown; discover windows before retrying", launchedPid, id, failure);
            throw new DesktopApplicationLaunchRejectedException(DesktopApplicationLaunchRejectedException.Reason.PRE_DISPATCH_FAILURE,
                    0, "Windows launch could not be prepared", failure);
        }
    }

    private Directory directory() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        TreeSet<String> registrations = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        boolean truncated = false;
        int inspected = 0;
        for (long hive : new long[]{0xffffffff80000001L, 0xffffffff80000002L}) {
            for (int view : new int[]{0x100, 0x200}) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment out = arena.allocate(P);
                    int opened = win.integer("advapi32", "RegOpenKeyExW", new MemoryLayout[]{P, P, I, I, P},
                            handle(hive), wide(arena, APP_PATHS), 0, 8 | view, out);
                    if (opened != 0) { truncated |= opened != 2 && opened != 3; continue; }
                    MemorySegment key = out.get(P, 0);
                    try {
                        for (int index = 0; index < 4096; index++) {
                            MemorySegment name = arena.allocate(514, 2);
                            MemorySegment length = arena.allocate(I);
                            length.set(I, 0, 256);
                            int status = win.integer("advapi32", "RegEnumKeyExW", new MemoryLayout[]{P, I, P, P, P, P, P, P},
                                    key, index, name, length, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                            if (status == 259) break;
                            if (++inspected > 4096) { truncated = true; break; }
                            if (status != 0) { truncated = true; continue; }
                            String value = wideString(name, 256);
                            if (validName(value)) registrations.add(value);
                        }
                    } finally { win.integer("advapi32", "RegCloseKey", new MemoryLayout[]{P}, key); }
                }
            }
        }
        for (String name : registrations) add(entries, stem(name), name, registered(name));
        Map<String, List<Path>> shortcuts = new LinkedHashMap<>();
        int visited = 0;
        for (String folder : List.of("a77f5d77-2e2b-44c3-a6a2-aba601054a51", "0139d44e-6afe-49f2-8690-3dafcae6ffb8")) {
            String root = knownFolder(folder);
            if (root.isEmpty()) { truncated = true; continue; }
            try (var files = Files.walk(Path.of(root), 8)) {
                var iterator = files.iterator();
                while (iterator.hasNext()) {
                    Path file = iterator.next();
                    if (++visited > 20000) { truncated = true; break; }
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || !file.toString().toLowerCase(Locale.ROOT).endsWith(".lnk")) continue;
                    String label = file.getFileName().toString();
                    label = label.substring(0, label.length() - 4);
                    if (validName(label)) shortcuts.computeIfAbsent(label.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(file);
                }
            } catch (IOException | RuntimeException failure) { truncated = true; }
        }
        for (var shortcut : shortcuts.values()) {
            if (shortcut.size() != 1) continue;
            Path file = shortcut.getFirst();
            String name = file.getFileName().toString();
            name = name.substring(0, name.length() - 4);
            String path = registered(name);
            if (path.isEmpty()) path = shortcut(file);
            add(entries, name, name, path);
        }
        return new Directory(List.copyOf(entries.values()), truncated);
    }

    private String registered(String input) {
        String name = input.toLowerCase(Locale.ROOT).endsWith(".exe") ? input : input + ".exe";
        for (long hive : new long[]{0xffffffff80000001L, 0xffffffff80000002L}) {
            for (int view : new int[]{0x100, 0x200}) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment out = arena.allocate(P);
                    int status = win.integer("advapi32", "RegOpenKeyExW", new MemoryLayout[]{P, P, I, I, P},
                            handle(hive), wide(arena, APP_PATHS + '\\' + name), 0, 1 | view, out);
                    if (status != 0) continue;
                    MemorySegment key = out.get(P, 0);
                    try {
                        MemorySegment text = arena.allocate(65536, 2), bytes = arena.allocate(I), type = arena.allocate(I);
                        bytes.set(I, 0, 65536);
                        status = win.integer("advapi32", "RegQueryValueExW", new MemoryLayout[]{P, P, P, P, P, P}, key,
                                MemorySegment.NULL, MemorySegment.NULL, type, text, bytes);
                        if (status == 0 && (type.get(I, 0) == 1 || type.get(I, 0) == 2)) {
                            String path = wideString(text, Math.min(32768, bytes.get(I, 0) / 2));
                            if (type.get(I, 0) == 2) {
                                MemorySegment expanded = arena.allocate(65536, 2);
                                int characters = win.integer("kernel32", "ExpandEnvironmentStringsW", new MemoryLayout[]{P, P, I},
                                        wide(arena, path), expanded, 32768);
                                if (characters < 1 || characters > 32768) continue;
                                path = wideString(expanded, characters);
                            }
                            if (executable(path)) return path;
                        }
                    } finally { win.integer("advapi32", "RegCloseKey", new MemoryLayout[]{P}, key); }
                }
            }
        }
        return "";
    }

    private String knownFolder(String guid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(P);
            if (win.integer("shell32", "SHGetKnownFolderPath", new MemoryLayout[]{P, I, P, P},
                    guid(arena, guid), 0, MemorySegment.NULL, out) < 0) return "";
            MemorySegment text = out.get(P, 0);
            try { return wideString(text, 32768); }
            finally { win.nothing("ole32", "CoTaskMemFree", new MemoryLayout[]{P}, text); }
        }
    }

    private String shortcut(Path file) {
        try (Arena arena = Arena.ofConfined(); Win32.Apartment apartment = win.apartment()) {
            MemorySegment out = arena.allocate(P);
            int status = win.integer("ole32", "CoCreateInstance", new MemoryLayout[]{P, P, I, P, P},
                    guid(arena, "00021401-0000-0000-c000-000000000046"), MemorySegment.NULL, 1,
                    guid(arena, "000214f9-0000-0000-c000-000000000046"), out);
            if (status < 0) return "";
            MemorySegment link = out.get(P, 0);
            try {
                if (win.com(link, 0, new MemoryLayout[]{P, P}, guid(arena, "0000010b-0000-0000-c000-000000000046"), out) < 0) return "";
                MemorySegment persist = out.get(P, 0);
                try {
                    if (win.com(persist, 5, new MemoryLayout[]{P, I}, wide(arena, file.toString()), 0) < 0) return "";
                } finally { win.release(persist); }
                MemorySegment args = arena.allocate(32768 * 2, 2), path = arena.allocate(32768 * 2, 2);
                if (win.com(link, 10, new MemoryLayout[]{P, I}, args, 32768) < 0 || !wideString(args, 32768).isEmpty()) return "";
                if (win.com(link, 3, new MemoryLayout[]{P, I, P, I}, path, 32768, MemorySegment.NULL, 4) < 0) return "";
                String value = wideString(path, 32768);
                return executable(value) ? value : "";
            } finally { win.release(link); }
        }
    }

    static boolean validName(String name) {
        return name != null && !name.isBlank() && name.equals(name.strip())
                && name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 256
                && !name.contains("..") && name.chars().noneMatch(ch -> ch < 32 || ch == 127 || ch == '/' || ch == '\\' || ch == ':');
    }

    static boolean executable(String path) {
        try {
            return path != null && path.length() > 6 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
                    && (path.charAt(2) == '\\' || path.charAt(2) == '/') && path.toLowerCase(Locale.ROOT).endsWith(".exe")
                    && Files.isRegularFile(Path.of(path), LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.InvalidPathException | SecurityException invalid) { return false; }
    }

    private static String fileName(String path) { return path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1); }
    private static String stem(String name) { return name.toLowerCase(Locale.ROOT).endsWith(".exe") ? name.substring(0, name.length() - 4) : name; }

    private static void add(Map<String, Entry> entries, String name, String launch, String path) {
        if (!executable(path)) return;
        String id = fileName(path).toLowerCase(Locale.ROOT);
        if (!validName(id) || !validName(launch)) return;
        if (!validName(name)) name = id;
        Entry previous = entries.get(id);
        TreeSet<String> aliases = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (previous != null) aliases.addAll(previous.aliases());
        aliases.addAll(List.of(id, name, launch, stem(id)));
        entries.put(id, new Entry(previous == null ? name : previous.name(), previous == null ? launch : previous.launch(),
                id, path, aliases.stream().limit(16).toList()));
    }

    private static DesktopApplicationLaunchRejectedException rejected(DesktopApplicationLaunchRejectedException.Reason reason, String detail) {
        return new DesktopApplicationLaunchRejectedException(reason, detail);
    }
    private record Directory(List<Entry> entries, boolean truncated) { }
    private record Entry(String name, String launch, String id, String path, List<String> aliases) {
        DesktopApplicationInfo metadata() { return new DesktopApplicationInfo(name, name, id, launch, aliases); }
    }
}
