package com.javaclaw.desktop.ffm.macos;

import com.javaclaw.desktop.api.DesktopApplicationCatalog;
import com.javaclaw.desktop.ffm.DesktopApplicationCatalogPolicy;
import com.javaclaw.desktop.api.DesktopApplicationInfo;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopApplicationLaunchRejectedException;
import com.javaclaw.desktop.api.DesktopApplicationLaunchUncertainException;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded application metadata in Java, with NSWorkspace used only for OS launch. */
final class MacApplications {
    private final MacNative api;
    private final MacWindows windows;
    MacApplications(MacNative api, MacWindows windows) { this.api = api; this.windows = windows; }

    DesktopApplicationCatalog catalog() {
        Scan scan = scan();
        return DesktopApplicationCatalogPolicy.bounded(scan.entries().stream().map(Entry::info).toList(),
                scan.truncated());
    }

    DesktopApplicationLaunch launch(String requested) {
        if (!identity(requested)) throw rejected(
                DesktopApplicationLaunchRejectedException.Reason.INVALID_ARGUMENTS,
                "Only an installed application name or bundle identifier is accepted");
        Scan inventory = scan();
        Map<String, Entry> exact = new LinkedHashMap<>();
        Entry registered = registeredApplication(requested);
        if (registered != null) exact.put(registered.info().applicationId().toLowerCase(Locale.ROOT), registered);
        inventory.entries().stream().filter(entry -> matches(entry.info(), requested)).forEach(entry ->
                exact.putIfAbsent(entry.info().applicationId().toLowerCase(Locale.ROOT), entry));
        List<Entry> matches = List.copyOf(exact.values());
        if (matches.isEmpty()) throw rejected(inventory.truncated()
                ? DesktopApplicationLaunchRejectedException.Reason.PRE_DISPATCH_FAILURE
                : DesktopApplicationLaunchRejectedException.Reason.APPLICATION_NOT_FOUND,
                inventory.truncated() ? "Application catalog is incomplete; exact launch identity cannot be confirmed"
                        : "No registered or installed application matches the exact name or bundle identifier");
        if (matches.size() != 1) throw rejected(
                DesktopApplicationLaunchRejectedException.Reason.AMBIGUOUS_APPLICATION,
                "Multiple applications match; use the exact bundle identifier");
        Entry selected = matches.getFirst();
        boolean dispatched = false;
        try (var pool = api.pool(); MacAsync callback = new MacAsync(api)) {
            MemorySegment url = api.object(api.cls("NSURL"), "fileURLWithPath:", api.string(selected.path().toString()));
            MemorySegment configuration = api.object(api.cls("NSWorkspaceOpenConfiguration"), "configuration");
            api.send(configuration, "setActivates:", (byte) 1);
            api.send(configuration, "setCreatesNewApplicationInstance:", (byte) 0);
            api.send(configuration, "setAllowsRunningApplicationSubstitution:", (byte) 0);
            api.send(configuration, "setPromptsUserIfNeeded:", (byte) 0);
            dispatched = true;
            api.send(api.object(api.cls("NSWorkspace"), "sharedWorkspace"),
                    "openApplicationAtURL:configuration:completionHandler:", url, configuration, callback.pointer());
            var result = callback.await(Duration.ofSeconds(12));
            if (MacNative.nil(result.pointer()) || api.bool(result.pointer(), "isTerminated"))
                throw new DesktopApplicationLaunchUncertainException(
                        "Launch was dispatched but not confirmed: " + result.error(), 0,
                        selected.info().applicationId(), null);
            long pid = api.integer(result.pointer(), "processIdentifier");
            String actualBundle = api.text(api.object(result.pointer(), "bundleIdentifier"));
            if (pid <= 0 || !actualBundle.equalsIgnoreCase(selected.info().applicationId())
                    || windows.instance(pid) == 0)
                throw new DesktopApplicationLaunchUncertainException(
                        "Launch was dispatched; running process identity is unconfirmed. Discover windows before retrying",
                        Math.max(0, pid), selected.info().applicationId(), null);
            return new DesktopApplicationLaunch(pid, actualBundle,
                    "Application launched or activated; discover its window before opening a session");
        } catch (DesktopApplicationLaunchUncertainException failure) { throw failure; }
        catch (RuntimeException | LinkageError failure) {
            if (dispatched) throw new DesktopApplicationLaunchUncertainException(
                    "Launch may have completed; discover windows before retrying", 0,
                    selected.info().applicationId(), failure);
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.PRE_DISPATCH_FAILURE,
                    0, "Application launch preparation failed before dispatch", failure);
        }
    }

    private Entry registeredApplication(String requested) {
        try (var pool = api.pool()) {
            MemorySegment workspace = api.object(api.cls("NSWorkspace"), "sharedWorkspace");
            MemorySegment url = api.object(workspace, "URLForApplicationWithBundleIdentifier:", api.string(requested));
            if (MacNative.nil(url)) {
                MemorySegment path = api.object(workspace, "fullPathForApplication:", api.string(requested));
                if (!MacNative.nil(path)) url = api.object(api.cls("NSURL"), "fileURLWithPath:", path);
            }
            if (MacNative.nil(url) || !api.bool(url, "isFileURL")) return null;
            Path path = Path.of(api.text(api.object(url, "path")));
            if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".app")
                    || !Files.isDirectory(path)) return null;
            Entry registered = bundle(path);
            return registered != null && matches(registered.info(), requested) ? registered : null;
        }
    }

    static boolean identity(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip()) && !value.contains("..")
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0 && value.indexOf(':') < 0
                && value.codePoints().noneMatch(Character::isISOControl)
                && value.getBytes(StandardCharsets.UTF_8).length <= 256;
    }

    static boolean matches(DesktopApplicationInfo info, String value) {
        return info.applicationId().equalsIgnoreCase(value) || info.name().equalsIgnoreCase(value)
                || info.displayName().equalsIgnoreCase(value)
                || info.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(value));
    }

    private Scan scan() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        boolean[] truncated = {false};
        int[] inspected = {0};
        try (var pool = api.pool()) {
            for (Path root : List.of(Path.of("/Applications"), Path.of("/System/Applications"),
                    Path.of(System.getProperty("user.home"), "Applications"))) {
                if (Files.isSymbolicLink(root)) { truncated[0] = true; continue; }
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    Files.walkFileTree(root, java.util.Set.of(), 5, new SimpleFileVisitor<>() {
                        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (++inspected[0] > 4096) { truncated[0] = true; return FileVisitResult.TERMINATE; }
                            if (dir.getFileName().toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                            if (dir.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".app")) {
                                Entry app = bundle(dir);
                                if (app != null) entries.merge(app.info().applicationId().toLowerCase(Locale.ROOT),
                                        app, MacApplications::merge);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isDirectory()) truncated[0] = true;
                            if (++inspected[0] > 4096) { truncated[0] = true; return FileVisitResult.TERMINATE; }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                            truncated[0] = true; return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException unavailable) { truncated[0] = true; }
                if (inspected[0] > 4096) break;
            }
        }
        return new Scan(List.copyOf(entries.values()), truncated[0]);
    }

    private Entry bundle(Path path) {
        MemorySegment bundle = api.object(api.cls("NSBundle"), "bundleWithPath:", api.string(path.toString()));
        if (MacNative.nil(bundle)) return null;
        String id = api.text(api.object(bundle, "bundleIdentifier"));
        if (!identity(id)) return null;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String fileName = path.getFileName().toString();
        add(names, fileName.substring(0, fileName.length() - 4));
        MemorySegment localized = api.object(bundle, "localizedInfoDictionary");
        MemorySegment plain = api.object(bundle, "infoDictionary");
        String display = first(localized, plain, "CFBundleDisplayName", "CFBundleName");
        for (MemorySegment dictionary : List.of(localized, plain))
            for (String key : List.of("CFBundleDisplayName", "CFBundleName", "CFBundleExecutable"))
                add(names, metadata(dictionary, key));
        add(names, id);
        localizedAliases(bundle, path, names);
        if (names.isEmpty()) return null;
        String name = names.getFirst();
        return new Entry(path, new DesktopApplicationInfo(name,
                identity(display) ? display : name, id, id, List.copyOf(names)));
    }

    private void localizedAliases(MemorySegment bundle, Path path, LinkedHashSet<String> names) {
        int inspected = 0;
        for (MemorySegment localization : api.array(api.object(bundle, "localizations"), 256)) {
            if (++inspected > 32 || names.size() >= 16) break;
            MemorySegment url = api.object(bundle, "URLForResource:withExtension:subdirectory:localization:",
                    api.string("InfoPlist"), api.string("strings"), MemorySegment.NULL, localization);
            if (MacNative.nil(url) || !api.bool(url, "isFileURL")) continue;
            try {
                Path metadata = Path.of(api.text(api.object(url, "path"))).toRealPath();
                if (!metadata.startsWith(path.toRealPath()) || Files.size(metadata) > 65536) continue;
                MemorySegment dictionary = api.object(api.cls("NSDictionary"), "dictionaryWithContentsOfURL:", url);
                add(names, metadata(dictionary, "CFBundleDisplayName"));
                add(names, metadata(dictionary, "CFBundleName"));
            } catch (IOException ignored) { /* Optional localized identity metadata. */ }
        }
    }

    private String first(MemorySegment localized, MemorySegment plain, String... keys) {
        for (MemorySegment dictionary : List.of(localized, plain))
            for (String key : keys) {
                String value = metadata(dictionary, key);
                if (identity(value)) return value;
            }
        return "";
    }

    private String metadata(MemorySegment dictionary, String key) {
        if (MacNative.nil(dictionary)) return "";
        MemorySegment value = api.object(dictionary, "objectForKey:", api.string(key));
        return !MacNative.nil(value) && api.bool(value, "isKindOfClass:", api.cls("NSString")) ? api.text(value) : "";
    }

    private static void add(LinkedHashSet<String> names, String value) {
        if (names.size() < 16 && identity(value)) names.add(value);
    }

    private static Entry merge(Entry first, Entry next) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>(first.info().aliases());
        next.info().aliases().forEach(alias -> add(aliases, alias));
        return new Entry(first.path(), new DesktopApplicationInfo(first.info().name(), first.info().displayName(),
                first.info().applicationId(), first.info().launchName(), List.copyOf(aliases)));
    }

    private static DesktopApplicationLaunchRejectedException rejected(
            DesktopApplicationLaunchRejectedException.Reason reason, String detail) {
        return new DesktopApplicationLaunchRejectedException(reason, detail);
    }
    private record Entry(Path path, DesktopApplicationInfo info) { }
    private record Scan(List<Entry> entries, boolean truncated) { }
}
