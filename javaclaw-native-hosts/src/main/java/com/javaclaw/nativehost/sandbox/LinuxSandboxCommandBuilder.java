package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/** 使用 bubblewrap 的 mount、PID、user 与 network namespace 构造 Linux 隔离命令。 */
final class LinuxSandboxCommandBuilder implements SandboxCommandBuilder {
    private static final List<Path> CANDIDATES = List.of(Path.of("/usr/bin/bwrap"), Path.of("/bin/bwrap"));
    private static final List<Path> SYSTEM_READ_ROOTS = List.of(
            Path.of("/usr"), Path.of("/bin"), Path.of("/sbin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc"));

    private final Path bubblewrap;

    LinuxSandboxCommandBuilder() {
        this(resolveBubblewrap());
    }

    LinuxSandboxCommandBuilder(Path bubblewrap) {
        this.bubblewrap = java.util.Objects.requireNonNull(bubblewrap, "bubblewrap");
    }

    @Override
    public SandboxLaunchPlan build(ValidatedSandboxCommand command) {
        if (!Files.isExecutable(bubblewrap)) {
            throw new UnsupportedOperationException("bubblewrap is unavailable");
        }
        if (!command.allowDelete() && !command.writeRoots().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Linux writable sandbox cannot distinguish write from unlink; allowDelete must be explicit");
        }
        ArrayList<String> result = new ArrayList<>();
        result.addAll(List.of(
                bubblewrap.toString(),
                "--die-with-parent",
                "--unshare-all",
                "--cap-drop",
                "ALL",
                "--proc",
                "/proc",
                "--dev",
                "/dev",
                "--clearenv"));
        if (command.mode() == com.javaclaw.api.SandboxMode.BATCH) {
            result.add("--new-session");
        }
        appendMounts(result, command);
        SandboxJavaRuntime runtime = helperRuntime();
        appendHelperMounts(result, runtime);
        command.environment().forEach((name, value) -> result.addAll(List.of("--setenv", name, value)));
        boolean proxy = command.networkAccess().mode() == SandboxNetworkAccess.Mode.PROXY_ONLY;
        if (proxy) {
            Path directory = command.networkAccess()
                    .controlDirectory()
                    .orElseThrow(() -> new SecurityException("Linux proxy namespace relay is not provisioned"));
            if (!Files.exists(directory.resolve("p"))) {
                throw new SecurityException("Linux proxy IPC socket is unavailable");
            }
            result.addAll(List.of("--ro-bind", directory.toString(), "/javaclaw-proxy"));
        }
        result.addAll(List.of("--chdir", command.workingDirectory().toString(), "--"));
        result.addAll(helperArguments(command, runtime, proxy));
        return new SandboxLaunchPlan(
                name(), SandboxHelperCommand.wrap(command, result), Map.of(), proxy ? 2 : 1, false);
    }

    private static SandboxJavaRuntime helperRuntime() {
        try {
            return SandboxJavaRuntime.forWorker(LinuxCommandExecMain.class);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException("cannot resolve Linux Sandbox helper runtime", failure);
        }
    }

    private static void appendHelperMounts(List<String> result, SandboxJavaRuntime runtime) {
        Set<Path> parents = new HashSet<>();
        for (Path root : runtime.readRoots()) {
            appendMount(result, root, false, parents);
        }
    }

    private static List<String> helperArguments(
            ValidatedSandboxCommand command, SandboxJavaRuntime runtime, boolean proxy) {
        if (!proxy) {
            return runtime.command(LinuxCommandExecMain.class, command.argv(), 32);
        }
        ArrayList<String> arguments = new ArrayList<>(List.of(
                "/javaclaw-proxy/p",
                Integer.toString(
                        command.networkAccess().proxyEndpoint().orElseThrow().getPort()),
                "--"));
        arguments.addAll(command.argv());
        return runtime.command(LinuxProxyExecMain.class, arguments, 32);
    }

    @Override
    public String name() {
        return "linux-bubblewrap";
    }

    private static Path resolveBubblewrap() {
        return CANDIDATES.stream().filter(Files::isExecutable).findFirst().orElse(CANDIDATES.getFirst());
    }

    private static void appendMounts(List<String> result, ValidatedSandboxCommand command) {
        HashMap<Path, Boolean> mounts = new HashMap<>();
        SYSTEM_READ_ROOTS.stream().filter(Files::exists).forEach(path -> mounts.put(path, false));
        command.readRoots().forEach(path -> mounts.put(path, false));
        command.writeRoots().forEach(path -> mounts.put(path, true));
        mounts.put(command.executable(), false);
        Set<Path> createdParents = new HashSet<>();
        mounts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Path, Boolean>>comparingInt(
                                entry -> entry.getKey().getNameCount())
                        .thenComparing(entry -> entry.getKey().toString()))
                .forEach(entry -> appendMount(result, entry.getKey(), entry.getValue(), createdParents));
    }

    private static void appendMount(List<String> result, Path source, boolean writable, Set<Path> createdParents) {
        Path path = source.toAbsolutePath().normalize();
        ArrayList<Path> parents = new ArrayList<>();
        for (Path parent = path.getParent();
                parent != null && parent.getParent() != null;
                parent = parent.getParent()) {
            parents.add(parent);
        }
        java.util.Collections.reverse(parents);
        for (Path parent : parents) {
            if (!underSystemRoot(parent) && createdParents.add(parent)) {
                result.addAll(List.of("--dir", parent.toString()));
            }
        }
        result.addAll(List.of(writable ? "--bind" : "--ro-bind", path.toString(), path.toString()));
    }

    private static boolean underSystemRoot(Path path) {
        return SYSTEM_READ_ROOTS.stream().anyMatch(path::startsWith);
    }
}
