package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.javaclaw.nativehost.ffm.LinuxSecurity;
import com.javaclaw.nativehost.ffm.NativeResourceLimits;
import com.javaclaw.nativehost.ffm.PosixPty;
import com.javaclaw.nativehost.ffm.WindowsSandbox;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxSessionOptions;

final class PlatformBackends {
    private PlatformBackends() {}

    static SandboxBackend current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return new MacSeatbeltBackend();
        }
        if (os.contains("linux")) {
            return new LinuxBubblewrapBackend();
        }
        if (os.contains("windows")) {
            return new WindowsAppContainerBackend();
        }
        return new UnavailableBackend("unsupported-platform");
    }

    static void verify(SandboxCommand command) {
        SandboxPolicy policy = command.policy();
        if (command.auxiliaryRole() == SandboxCommand.AuxiliaryRole.BROWSER
                && (!command.argv().getLast().equals("com.javaclaw.browser.BrowserServiceMain")
                        || policy.network().mode() != NetworkPolicy.Mode.DISABLED)) {
            throw new IllegalArgumentException("Browser role requires the fixed networkless worker entrypoint");
        }
        Path cwd = command.workingDirectory();
        boolean cwdReadable = policy.mode() == SandboxMode.HOST_FULL_ACCESS
                || policy.readableRoots().stream().anyMatch(cwd::startsWith)
                || policy.writableRoots().stream().anyMatch(cwd::startsWith);
        if (!cwdReadable) {
            throw new IllegalArgumentException("working directory is outside sandbox roots");
        }
        if (policy.protectedRoots().stream().anyMatch(cwd::startsWith)) {
            throw new IllegalArgumentException("working directory is protected");
        }
    }

    private static final class MacSeatbeltBackend implements SandboxBackend {
        private static final Path EXECUTABLE = Path.of("/usr/bin/sandbox-exec");

        @Override
        public String name() {
            return "macos-seatbelt";
        }

        @Override
        public boolean available() {
            return Files.isExecutable(EXECUTABLE);
        }

        @Override
        public List<String> wrap(SandboxCommand command) {
            verify(command);
            NetworkPolicy.Mode network = command.policy().network().mode();
            if (network == NetworkPolicy.Mode.ALLOWLIST || network == NetworkPolicy.Mode.LOOPBACK) {
                throw new UnsupportedOperationException("Seatbelt backend cannot exactly enforce this network policy");
            }
            List<String> target = helperAvailable() ? processGroupHelper(command.argv()) : command.argv();
            return join(List.of(EXECUTABLE.toString(), "-p", profile(command, null), "--"), target);
        }

        @Override
        public List<String> wrapSession(SandboxCommand command, SandboxSessionOptions options, Path terminal) {
            if (!options.pseudoTerminal()) {
                return wrap(command);
            }
            verify(command);
            if (!PosixPty.isSupported()) {
                throw new UnsupportedOperationException("macOS PTY support is unavailable");
            }
            NetworkPolicy.Mode network = command.policy().network().mode();
            if (network == NetworkPolicy.Mode.ALLOWLIST || network == NetworkPolicy.Mode.LOOPBACK) {
                throw new UnsupportedOperationException("Seatbelt backend cannot exactly enforce this network policy");
            }
            return join(
                    List.of(EXECUTABLE.toString(), "-p", profile(command, terminal), "--"),
                    ptyHelper(command.argv(), options));
        }

        private static String profile(SandboxCommand command, Path terminal) {
            SandboxPolicy policy = command.policy();
            StringBuilder value = new StringBuilder("(version 1)\n(deny default)\n");
            value.append("(allow process-exec process-fork)\n");
            value.append("(allow process-info* (target same-sandbox))\n(allow signal (target same-sandbox))\n");
            value.append("(allow sysctl-read)\n");
            // 不允许任意 XPC 服务代替子进程联网或访问凭据；只开放 JVM 基础运行所需的系统通知与日志。
            value.append("(allow mach-lookup (global-name \"com.apple.system.opendirectoryd.libinfo\"))\n");
            value.append(
                    "(allow mach-lookup (global-name \"com.apple.system.logger\") (global-name \"com.apple.logd\") (global-name \"com.apple.system.notification_center\") (global-name \"com.apple.cfprefsd.daemon\") (global-name \"com.apple.cfprefsd.agent\"))\n");
            if (command.auxiliaryRole() == SandboxCommand.AuxiliaryRole.BROWSER) {
                String privateRoot = escape(command.workingDirectory().toString());
                value.append(
                        "(allow mach-lookup (global-name \"com.apple.coreservices.launchservicesd\") (global-name \"com.apple.lsd.mapdb\") (global-name \"com.apple.hiservices-xpcservice\"))\n");
                value.append("(allow system-socket (socket-domain AF_UNIX))\n");
                value.append("(allow network-bind (local unix-socket (subpath \"")
                        .append(privateRoot)
                        .append("\")))\n");
                value.append("(allow network-outbound (remote unix-socket (subpath \"")
                        .append(privateRoot)
                        .append("\")))\n");
                value.append(
                        "(allow mach-lookup (global-name \"com.apple.windowserver.active\") (global-name \"com.apple.PowerManagement.control\") (global-name \"com.apple.fonts\") (global-name \"com.apple.FontObjectsServer\") (global-name-regex #\"^org\\.chromium\\.Chromium\\.MachPortRendezvousServer\\.[0-9]+$\"))\n");
                // 只向固定 Browser Worker 提供电源通知和 Chromium 子进程 rendezvous；普通 Shell 不能申请此角色。
                value.append("(allow iokit-open (iokit-user-client-class \"RootDomainUserClient\"))\n");
                value.append("(allow ipc-posix-shm-read-data (ipc-posix-name \"apple.shm.notification_center\"))\n");
                value.append("(allow file-read-metadata (literal \"/\") (literal \"/var\") (literal \"/etc\"))\n");
                value.append(
                        "(allow mach-register (global-name-regex #\"^org\\.chromium\\.Chromium\\.MachPortRendezvousServer\\.[0-9]+$\"))\n");
                value.append("(deny iokit-open (iokit-user-client-class \"IOHIDParamUserClient\"))\n");
            }
            // dyld probes the root directory itself before following system-library paths.
            // This exposes only the root directory entry list, not arbitrary file contents.
            value.append("(allow file-read-data (literal \"/\"))\n");
            for (String root : List.of(
                    "/System", "/usr", "/bin", "/sbin", "/Library", "/private/etc", "/private/var/select", "/dev")) {
                appendReadRoot(value, Path.of(root));
            }
            helperReadRoots().forEach(path -> appendReadRoot(value, path));
            if (policy.mode() == SandboxMode.HOST_FULL_ACCESS) {
                value.append("(allow file-read*)\n(allow file-write*)\n");
            } else {
                policy.readableRoots().forEach(path -> appendReadRoot(value, path));
                policy.writableRoots().forEach(path -> appendWritableRoot(value, path));
            }
            policy.protectedRoots().forEach(path -> {
                String protectedPath = escape(path.toString());
                // A protected root can be either a directory or a file (notably a Git
                // worktree's .git file), so both the exact path and its descendants matter.
                value.append("(deny file-write* (literal \"")
                        .append(protectedPath)
                        .append("\"))\n");
                value.append("(deny file-write* (subpath \"")
                        .append(protectedPath)
                        .append("\"))\n");
            });
            if (terminal != null) {
                String pty = escape(terminal.toAbsolutePath().normalize().toString());
                value.append("(allow file-read* file-write* file-ioctl (literal \"")
                        .append(pty)
                        .append("\"))\n");
            }
            if (policy.network().mode() == NetworkPolicy.Mode.FULL) {
                value.append("(allow network*)\n");
            }
            return value.toString();
        }

        private static void appendReadRoot(StringBuilder profile, Path root) {
            appendAncestorMetadata(profile, root);
            String path = escape(root.toAbsolutePath().normalize().toString());
            profile.append("(allow file-read* (literal \"").append(path).append("\"))\n");
            profile.append("(allow file-read* (subpath \"").append(path).append("\"))\n");
        }

        private static void appendWritableRoot(StringBuilder profile, Path root) {
            appendAncestorMetadata(profile, root);
            String path = escape(root.toAbsolutePath().normalize().toString());
            profile.append("(allow file-read* file-write* (literal \"")
                    .append(path)
                    .append("\"))\n");
            profile.append("(allow file-read* file-write* (subpath \"")
                    .append(path)
                    .append("\"))\n");
        }

        private static void appendAncestorMetadata(StringBuilder profile, Path root) {
            ArrayList<Path> ancestors = new ArrayList<>();
            for (Path parent = root.toAbsolutePath().normalize().getParent();
                    parent != null && parent.getParent() != null;
                    parent = parent.getParent()) {
                ancestors.add(parent);
            }
            java.util.Collections.reverse(ancestors);
            ancestors.forEach(path -> profile.append("(allow file-read-metadata (literal \"")
                    .append(escape(path.toString()))
                    .append("\"))\n"));
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static boolean helperAvailable() {
            return pathProperty() != null;
        }

        private static List<String> processGroupHelper(List<String> target) {
            return helperCommand("com.javaclaw.nativehost.sandbox.ProcessGroupExecMain", target);
        }

        private static List<String> ptyHelper(List<String> target, SandboxSessionOptions options) {
            return helperCommand(
                    "com.javaclaw.nativehost.sandbox.PosixPtyExecMain",
                    join(List.of(Integer.toString(options.columns()), Integer.toString(options.rows()), "--"), target));
        }

        private static List<String> helperCommand(String mainClass, List<String> target) {
            ArrayList<String> result = new ArrayList<>();
            result.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            String modules = System.getProperty("jdk.module.path");
            if (modules != null
                    && !modules.isBlank()
                    && PlatformBackends.class.getModule().isNamed()) {
                result.add("--enable-native-access=com.javaclaw.nativehosts");
                result.addAll(List.of("--module-path", modules, "-m", "com.javaclaw.nativehosts/" + mainClass));
            } else {
                result.addAll(List.of("-cp", System.getProperty("java.class.path"), mainClass));
            }
            result.addAll(target);
            return List.copyOf(result);
        }

        private static List<Path> helperReadRoots() {
            ArrayList<Path> result = new ArrayList<>();
            addExisting(result, Path.of(System.getProperty("java.home")));
            addCodeRoot(result, PlatformBackends.class);
            addCodeRoot(result, NativeResourceLimits.class);
            String paths = pathProperty();
            if (paths != null) {
                for (String value : paths.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    if (!value.isBlank()) {
                        addExisting(result, Path.of(value));
                    }
                }
            }
            ArrayList<Path> ordered = result.stream()
                    .distinct()
                    .sorted(java.util.Comparator.comparingInt(Path::getNameCount))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            ArrayList<Path> collapsed = new ArrayList<>();
            for (Path candidate : ordered) {
                if (collapsed.stream().noneMatch(candidate::startsWith)) {
                    collapsed.add(candidate);
                }
            }
            return List.copyOf(collapsed);
        }

        private static String pathProperty() {
            String modules = System.getProperty("jdk.module.path");
            if (modules != null && !modules.isBlank()) {
                return modules;
            }
            String classes = System.getProperty("java.class.path");
            return classes == null || classes.isBlank() ? null : classes;
        }

        private static void addCodeRoot(List<Path> roots, Class<?> type) {
            try {
                addExisting(
                        roots,
                        Path.of(type.getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI()));
            } catch (Exception ignored) {
            }
        }

        private static void addExisting(List<Path> roots, Path value) {
            try {
                Path real = value.toRealPath();
                roots.add(Files.isDirectory(real) ? real : real.getParent());
            } catch (Exception ignored) {
            }
        }
    }

    private static final class LinuxBubblewrapBackend implements SandboxBackend {
        private final Path executable = locate("/usr/bin/bwrap", "/bin/bwrap");

        @Override
        public String name() {
            return "linux-bubblewrap-seccomp";
        }

        @Override
        public boolean available() {
            return executable != null && helperAvailable() && LinuxSecurity.isSupported();
        }

        @Override
        public List<String> wrap(SandboxCommand command) {
            return wrap(command, false);
        }

        @Override
        public List<String> wrapSession(SandboxCommand command, SandboxSessionOptions options, Path terminal) {
            if (!options.pseudoTerminal()) {
                return wrap(command);
            }
            if (!PosixPty.isSupported()) {
                throw new UnsupportedOperationException("Linux PTY support is unavailable");
            }
            return wrap(command, true, options);
        }

        private List<String> wrap(SandboxCommand command, boolean pseudoTerminal) {
            return wrap(command, pseudoTerminal, null);
        }

        private List<String> wrap(SandboxCommand command, boolean pseudoTerminal, SandboxSessionOptions options) {
            verify(command);
            SandboxPolicy policy = command.policy();
            if (policy.network().mode() == NetworkPolicy.Mode.ALLOWLIST
                    || policy.network().mode() == NetworkPolicy.Mode.LOOPBACK) {
                throw new UnsupportedOperationException(
                        "bubblewrap backend cannot exactly enforce this network policy");
            }
            List<String> result = new ArrayList<>();
            result.addAll(List.of(executable.toString(), "--die-with-parent"));
            if (!pseudoTerminal) {
                result.add("--new-session");
            }
            result.addAll(
                    List.of("--unshare-all", "--cap-drop", "ALL", "--proc", "/proc", "--dev", "/dev", "--clearenv"));
            if (policy.network().mode() == NetworkPolicy.Mode.FULL) {
                result.add("--share-net");
            }
            if (policy.mode() == SandboxMode.HOST_FULL_ACCESS) {
                result.addAll(List.of("--bind", "/", "/"));
            } else {
                for (String root : List.of("/usr", "/bin", "/lib", "/lib64", "/etc")) {
                    if (Files.exists(Path.of(root))) {
                        result.addAll(List.of("--ro-bind", root, root));
                    }
                }
                policy.readableRoots()
                        .forEach(path -> result.addAll(List.of("--ro-bind", path.toString(), path.toString())));
                policy.writableRoots()
                        .forEach(path -> result.addAll(List.of("--bind", path.toString(), path.toString())));
            }
            // The trusted Java helper is mounted read-only, then installs seccomp from inside
            // the new namespace before atomically execing the untrusted target.
            java.util.HashSet<Path> createdDirectories = new java.util.HashSet<>();
            helperReadRoots().forEach(path -> addInfrastructureBind(result, path, createdDirectories));
            // Re-apply immutable roots after every writable or host bind. Ordering is security-critical.
            for (Path path : policy.protectedRoots()) {
                boolean exposedToWrites = policy.mode() == SandboxMode.HOST_FULL_ACCESS
                        || policy.writableRoots().stream().anyMatch(path::startsWith);
                if (!Files.exists(path)) {
                    if (exposedToWrites) {
                        throw new UnsupportedOperationException(
                                "bubblewrap cannot protect a non-existing writable path: " + path);
                    }
                    continue;
                }
                result.addAll(List.of("--ro-bind", path.toString(), path.toString()));
            }
            command.policy()
                    .filteredEnvironment(command.environment())
                    .forEach((name, value) -> result.addAll(List.of("--setenv", name, value)));
            result.addAll(List.of("--chdir", command.workingDirectory().toString(), "--"));
            result.addAll(seccompHelper(command.argv(), pseudoTerminal, options));
            return List.copyOf(result);
        }

        private static boolean helperAvailable() {
            return pathProperty() != null;
        }

        private static List<String> seccompHelper(
                List<String> target, boolean pseudoTerminal, SandboxSessionOptions options) {
            ArrayList<String> result = new ArrayList<>();
            result.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            String mainClass = pseudoTerminal
                    ? "com.javaclaw.nativehost.sandbox.PosixPtyExecMain"
                    : "com.javaclaw.nativehost.sandbox.LinuxSandboxExecMain";
            String modules = System.getProperty("jdk.module.path");
            if (modules != null
                    && !modules.isBlank()
                    && PlatformBackends.class.getModule().isNamed()) {
                result.add("--enable-native-access=com.javaclaw.nativehosts");
                result.addAll(List.of("--module-path", modules, "-m", "com.javaclaw.nativehosts/" + mainClass));
            } else {
                result.addAll(List.of("-cp", System.getProperty("java.class.path"), mainClass));
            }
            if (pseudoTerminal) {
                if (options == null) {
                    throw new IllegalArgumentException("PTY session options are missing");
                }
                result.addAll(List.of(Integer.toString(options.columns()), Integer.toString(options.rows()), "--"));
            }
            result.addAll(target);
            return result;
        }

        private static List<Path> helperReadRoots() {
            ArrayList<Path> result = new ArrayList<>();
            addExisting(result, Path.of(System.getProperty("java.home")));
            addCodeRoot(result, PlatformBackends.class);
            addCodeRoot(result, LinuxSecurity.class);
            String paths = pathProperty();
            if (paths != null) {
                for (String value : paths.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    if (!value.isBlank()) {
                        addExisting(result, Path.of(value));
                    }
                }
            }
            ArrayList<Path> ordered = result.stream()
                    .distinct()
                    .sorted(java.util.Comparator.comparingInt(Path::getNameCount))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            ArrayList<Path> collapsed = new ArrayList<>();
            for (Path candidate : ordered) {
                if (collapsed.stream().noneMatch(candidate::startsWith)) {
                    collapsed.add(candidate);
                }
            }
            return List.copyOf(collapsed);
        }

        private static String pathProperty() {
            String modules = System.getProperty("jdk.module.path");
            if (modules != null && !modules.isBlank()) {
                return modules;
            }
            String classes = System.getProperty("java.class.path");
            return classes == null || classes.isBlank() ? null : classes;
        }

        private static void addInfrastructureBind(
                List<String> result, Path path, java.util.Set<Path> createdDirectories) {
            Path absolute = path.toAbsolutePath().normalize();
            if (systemPath(absolute)) {
                return;
            }
            ArrayList<Path> missing = new ArrayList<>();
            for (Path parent = absolute.getParent();
                    parent != null && parent.getParent() != null && !systemPath(parent);
                    parent = parent.getParent()) {
                missing.add(parent);
            }
            java.util.Collections.reverse(missing);
            for (Path directory : missing) {
                if (createdDirectories.add(directory)) {
                    result.addAll(List.of("--dir", directory.toString()));
                }
            }
            result.addAll(List.of("--ro-bind", absolute.toString(), absolute.toString()));
        }

        private static boolean systemPath(Path path) {
            return List.of("/usr", "/bin", "/lib", "/lib64", "/etc").stream()
                    .map(Path::of)
                    .anyMatch(path::startsWith);
        }

        private static void addCodeRoot(List<Path> roots, Class<?> type) {
            try {
                addExisting(
                        roots,
                        Path.of(type.getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI()));
            } catch (Exception ignored) {
            }
        }

        private static void addExisting(List<Path> roots, Path value) {
            try {
                Path real = value.toRealPath();
                roots.add(Files.isDirectory(real) ? real : real.getParent());
            } catch (Exception ignored) {
            }
        }

        private static Path locate(String... candidates) {
            for (String candidate : candidates) {
                Path path = Path.of(candidate);
                if (Files.isExecutable(path)) {
                    return path;
                }
            }
            return null;
        }
    }

    private static final class WindowsAppContainerBackend implements SandboxBackend {
        @Override
        public String name() {
            return "windows-appcontainer";
        }

        @Override
        public boolean available() {
            return WindowsSandbox.isSupported() && helperAvailable();
        }

        @Override
        public List<String> wrap(SandboxCommand command) {
            return wrap(command, null);
        }

        @Override
        public List<String> wrapSession(SandboxCommand command, SandboxSessionOptions options, Path terminal) {
            if (!options.pseudoTerminal()) {
                return wrap(command);
            }
            return wrap(command, options);
        }

        private List<String> wrap(SandboxCommand command, SandboxSessionOptions sessionOptions) {
            verify(command);
            SandboxPolicy policy = command.policy();
            if (policy.mode() == SandboxMode.HOST_FULL_ACCESS) {
                throw new UnsupportedOperationException(
                        "Windows HOST_FULL_ACCESS cannot preserve permanent protected roots");
            }
            if (policy.network().mode() != NetworkPolicy.Mode.DISABLED) {
                throw new UnsupportedOperationException(
                        "Windows sandbox processes have no raw network; use NetworkBroker");
            }
            if (!available()) {
                throw new UnsupportedOperationException("Windows AppContainer helper is unavailable");
            }
            ArrayList<String> result = new ArrayList<>();
            result.add(
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
            String modules = System.getProperty("jdk.module.path");
            if (modules != null
                    && !modules.isBlank()
                    && PlatformBackends.class.getModule().isNamed()) {
                result.add("--enable-native-access=com.javaclaw.nativehosts");
                result.addAll(List.of(
                        "--module-path",
                        modules,
                        "-m",
                        "com.javaclaw.nativehosts/" + "com.javaclaw.nativehost.sandbox.WindowsSandboxExecMain"));
            } else {
                result.addAll(List.of(
                        "-cp",
                        System.getProperty("java.class.path"),
                        "com.javaclaw.nativehost.sandbox.WindowsSandboxExecMain"));
            }
            result.addAll(List.of(
                    "--cwd",
                    command.workingDirectory().toString(),
                    "--timeout-millis",
                    Long.toString(policy.timeout().toMillis())));
            if (sessionOptions != null) {
                result.addAll(List.of(
                        "--pty",
                        "true",
                        "--columns",
                        Integer.toString(sessionOptions.columns()),
                        "--rows",
                        Integer.toString(sessionOptions.rows())));
            }
            policy.readableRoots().forEach(path -> result.addAll(List.of("--read-root", path.toString())));
            policy.writableRoots().forEach(path -> result.addAll(List.of("--write-root", path.toString())));
            policy.protectedRoots().forEach(path -> result.addAll(List.of("--protected-root", path.toString())));
            result.add("--");
            result.addAll(command.argv());
            return List.copyOf(result);
        }

        private static boolean helperAvailable() {
            String modules = System.getProperty("jdk.module.path");
            String classes = System.getProperty("java.class.path");
            return (modules != null && !modules.isBlank()) || (classes != null && !classes.isBlank());
        }
    }

    private record UnavailableBackend(String name) implements SandboxBackend {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public List<String> wrap(SandboxCommand command) {
            throw new UnsupportedOperationException("sandbox backend is unavailable: " + name);
        }
    }

    private static List<String> join(List<String> prefix, List<String> suffix) {
        List<String> result = new ArrayList<>(prefix.size() + suffix.size());
        result.addAll(prefix);
        result.addAll(suffix);
        return List.copyOf(result);
    }
}
