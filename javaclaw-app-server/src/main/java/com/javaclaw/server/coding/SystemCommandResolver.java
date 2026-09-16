package com.javaclaw.server.coding;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingSystemContracts;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Catalog;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Executable;
import com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CodingSystemService;
import com.javaclaw.server.system.SystemCommandCatalog;

/** 系统入口解析；登记仅固定身份，进程和依赖读取仍取本 Turn 的有效权限，所有调用离线。 */
final class SystemCommandResolver {
    private final CodingSystemService service;
    private final SystemCommandCatalog discovery;
    private final Path dataRoot;
    private final CanonicalJson json;
    private final Clock clock;

    SystemCommandResolver(CodingSystemService service, Path dataRoot, CanonicalJson json, Clock clock) {
        this.service = service;
        this.discovery = new SystemCommandCatalog(dataRoot);
        this.dataRoot = dataRoot;
        this.json = json;
        this.clock = clock;
    }

    CodingToolResult execute(CodingInvocation invocation, CodingProcessManager processes) throws Exception {
        return switch (invocation.request().tool().name()) {
            case "system_command_list" -> {
                json.decode(
                        invocation.request().arguments(),
                        com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Empty.class);
                yield CodingToolResult.value(list(invocation));
            }
            case "system_command_run" -> {
                var input = json.decode(invocation.request().arguments(), CodingSystemContracts.CommandRun.class);
                List<String> argv = new ArrayList<>(List.of(input.executableId()));
                argv.addAll(input.arguments());
                var summary = new CodingContracts.CommandRun(
                        argv, input.workingDirectory(), input.timeoutSeconds(), input.maxOutputBytes());
                yield processes.run(invocation, summary, () -> resolveCommand(invocation, input));
            }
            case "system_shell_run" -> {
                var input = json.decode(invocation.request().arguments(), CodingSystemContracts.ShellRun.class);
                var summary = new CodingContracts.CommandRun(
                        List.of("system-shell", input.command()),
                        input.workingDirectory(),
                        input.timeoutSeconds(),
                        input.maxOutputBytes());
                yield processes.run(invocation, summary, () -> resolveShell(invocation, input));
            }
            default -> throw new SecurityException("未知系统程序工具");
        };
    }

    Catalog list(CodingInvocation invocation) {
        Catalog frozen = frozen(invocation);
        List<Executable> entries = frozen.executables().stream()
                .filter(entry -> permitted(invocation.permission(), entry))
                .map(entry -> visible(invocation, entry))
                .toList();
        return new Catalog(frozen.platform(), frozen.registryRevision(), entries);
    }

    CodingResolvedCommand resolveCommand(CodingInvocation invocation, CodingSystemContracts.CommandRun input)
            throws Exception {
        Catalog frozen = frozen(invocation);
        Executable entry = frozen.executables().stream()
                .filter(value -> value.id().equals(input.executableId()))
                .findFirst()
                .orElseThrow(() -> new SecurityException("SYSTEM_EXECUTABLE_NOT_FROZEN: " + input.executableId()));
        requirePermission(invocation, entry);
        Path executable = discovery.verify(entry);
        ArrayList<String> argv = new ArrayList<>(List.of(executable.toString()));
        argv.addAll(input.arguments());
        return resolved(
                invocation,
                frozen,
                entry,
                argv,
                new Options(input.workingDirectory(), input.timeoutSeconds(), input.maxOutputBytes()),
                List.of(entry));
    }

    CodingResolvedCommand resolveShell(CodingInvocation invocation, CodingSystemContracts.ShellRun input)
            throws Exception {
        Catalog frozen = frozen(invocation);
        String shellId = SystemCommandCatalog.platform().equals("windows") ? "system.cmd" : "system.sh";
        Executable shell = frozen.executables().stream()
                .filter(entry -> entry.id().equals(shellId))
                .findFirst()
                .orElseThrow(() -> new SecurityException("SYSTEM_SHELL_NOT_FROZEN"));
        requirePermission(invocation, shell);
        Path executable = discovery.verify(shell);
        List<String> argv = SystemCommandCatalog.platform().equals("windows")
                ? List.of(executable.toString(), "/d", "/s", "/c", input.command())
                : List.of(executable.toString(), "-c", input.command());
        List<Executable> entries = new ArrayList<>();
        for (Executable entry : frozen.executables()) {
            if (!entry.available() || !readableDependencies(invocation.permission(), entry)) {
                continue;
            }
            // 显式 Shell 授权覆盖同一 Sandbox 的进程树；不能再把逻辑程序 ID 当作逐条子命令白名单。
            invocation.cancellation().throwIfCancelled();
            discovery.verify(entry);
            entries.add(entry);
        }
        return resolved(
                invocation,
                frozen,
                shell,
                argv,
                new Options(input.workingDirectory(), input.timeoutSeconds(), input.maxOutputBytes()),
                entries);
    }

    private CodingResolvedCommand resolved(
            CodingInvocation invocation,
            Catalog catalog,
            Executable entry,
            List<String> argv,
            Options options,
            List<Executable> runnable)
            throws Exception {
        invocation.cancellation().throwIfCancelled();
        Path root = invocation.turn().executionRoot();
        CodingDataBoundary.requireOutside(root, invocation.permission(), dataRoot.resolve("coding"));
        Path cwd = root.resolve(options.cwd()).normalize();
        if (!cwd.startsWith(root) || !cwd.toRealPath().startsWith(root.toRealPath())) {
            throw new SecurityException("系统命令目录超出执行根");
        }
        List<Path> reads = new ArrayList<>();
        List<Path> executableRoots = new ArrayList<>();
        List<Executable> runtimeEntries = shellRuntime(catalog, entry, runnable);
        for (Executable candidate : runtimeEntries) {
            Path executable = Path.of(candidate.realPath().orElseThrow());
            if (!trustedWindowsSystemFile(executable)) {
                reads.add(executable);
                executableRoots.add(executable);
            }
            reads.addAll(dependencies(invocation.permission(), candidate));
        }
        String snapshotDigest = json.encode(catalog).sha256();
        Path cache = dataRoot.resolve("coding/caches")
                .resolve(invocation.workspaceId().toString())
                .resolve("system-" + snapshotDigest);
        var directories = new ManagedCommandDirectories(dataRoot)
                .prepare(cache, invocation.turn().id());
        var command = new SandboxCommand(
                invocation.id(),
                argv,
                cwd,
                environment(directories),
                new byte[0],
                SandboxMode.BATCH,
                timeout(invocation, options.seconds()));
        PermissionProfile permission = permission(invocation.permission(), argv.getFirst(), options.bytes());
        var access = new SandboxRuntimeAccess(
                reads.stream().distinct().toList(),
                List.of(directories.cache(), directories.temporary()),
                executableRoots.stream().distinct().toList());
        CanonicalPayload evidence = json.encode(new Evidence(snapshotDigest, entry, runtimeEntries));
        return new Resolved(command, permission, access, entry.outputEncoding(), Optional.of(evidence));
    }

    private Catalog frozen(CodingInvocation invocation) {
        invocation.cancellation().throwIfCancelled();
        Catalog catalog = service.frozen(invocation.turn().id());
        if (!catalog.platform().equals(SystemCommandCatalog.platform())) {
            throw new SecurityException("SYSTEM_SNAPSHOT_UNAVAILABLE: 系统程序快照缺失或平台已改变");
        }
        return catalog;
    }

    private Executable visible(CodingInvocation invocation, Executable entry) {
        if (!entry.available()) {
            return entry;
        }
        try {
            invocation.cancellation().throwIfCancelled();
            discovery.verify(entry);
            dependencies(invocation.permission(), entry);
            return entry;
        } catch (IOException | SecurityException failure) {
            return new Executable(
                    entry.id(),
                    entry.path(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    entry.source(),
                    entry.readRoots(),
                    entry.outputEncoding(),
                    false,
                    Optional.of("SYSTEM_EXECUTABLE_UNAVAILABLE"));
        }
    }

    private static boolean permitted(PermissionProfile permission, Executable entry) {
        return permission.processes().executables().contains(entry.id());
    }

    private static void requirePermission(CodingInvocation invocation, Executable entry) {
        invocation.cancellation().throwIfCancelled();
        if (!permitted(invocation.permission(), entry)) {
            throw new SecurityException("SYSTEM_PROCESS_DENIED: 登记不授予程序执行权");
        }
    }

    private static List<Path> dependencies(PermissionProfile permission, Executable entry) throws IOException {
        List<Path> allowed = new ArrayList<>();
        for (Path root : java.util.stream.Stream.concat(
                        permission.files().readRoots().stream(), permission.files().writeRoots().stream())
                .toList()) {
            allowed.add(root.toRealPath());
        }
        List<Path> reads = new ArrayList<>();
        for (String dependency : entry.readRoots()) {
            Path requested = Path.of(dependency).toRealPath();
            if (!Files.isDirectory(requested) || allowed.stream().noneMatch(requested::startsWith)) {
                throw new SecurityException("SYSTEM_RUNTIME_READ_DENIED: 依赖目录未获本次文件读取授权");
            }
            reads.add(requested);
        }
        return List.copyOf(reads);
    }

    private static boolean readableDependencies(PermissionProfile permission, Executable entry) {
        try {
            dependencies(permission, entry);
            return true;
        } catch (IOException | SecurityException denied) {
            // 未获依赖读取授权的登记入口不装入 Shell 运行时，也不阻止使用其他已冻结入口。
            return false;
        }
    }

    private static boolean trustedWindowsSystemFile(Path executable) throws IOException {
        // Windows 已按固定系统目录的现有 ACL 读取系统入口；不能尝试为普通调用者修改 System32 DACL。
        return SystemCommandCatalog.platform().equals("windows")
                && executable.startsWith(
                        SystemCommandCatalog.shell().getParent().toRealPath());
    }

    private static Map<String, String> environment(ManagedCommandDirectories.Prepared directories) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(
                "PATH",
                String.join(
                        File.pathSeparator,
                        SystemCommandCatalog.systemPath().stream()
                                .map(Path::toString)
                                .toList()));
        values.put("HOME", directories.cache().toString());
        values.put("USERPROFILE", directories.cache().toString());
        values.put("TMPDIR", directories.temporary().toString());
        values.put("TMP", directories.temporary().toString());
        values.put("TEMP", directories.temporary().toString());
        values.put("LANG", "en_US.UTF-8");
        if (SystemCommandCatalog.platform().equals("windows")) {
            values.put(
                    "SystemRoot",
                    SystemCommandCatalog.shell().getParent().getParent().toString());
            values.put("COMSPEC", SystemCommandCatalog.shell().toString());
            values.put("NoDefaultCurrentDirectoryInExePath", "1");
        }
        return Map.copyOf(values);
    }

    private List<Executable> shellRuntime(Catalog catalog, Executable entry, List<Executable> runnable) {
        if (!entry.id().equals("system.sh") || !SystemCommandCatalog.platform().equals("macos")) {
            return runnable;
        }
        List<Executable> result = new ArrayList<>(runnable);
        // macOS sh 的固定系统选择器可能转入 bash 或 zsh；两个运行入口都必须来自同一冻结目录。
        for (Executable candidate : catalog.executables()) {
            if (Set.of("system.bash", "system.zsh").contains(candidate.id()) && candidate.available()) {
                discovery.verify(candidate);
                if (!result.contains(candidate)) {
                    result.add(candidate);
                }
            }
        }
        return List.copyOf(result);
    }

    private Duration timeout(CodingInvocation invocation, int seconds) {
        Duration remaining = Duration.between(
                clock.instant(),
                invocation.turn().createdAt().plus(invocation.turn().budget().wallTime()));
        if (remaining.isNegative() || remaining.isZero()) {
            throw new SecurityException("TURN_DEADLINE_EXCEEDED");
        }
        Duration requested = Duration.ofSeconds(seconds);
        Duration maximum = invocation.permission().processes().maxRunTime();
        return requested.compareTo(remaining) < 0
                ? requested.compareTo(maximum) < 0 ? requested : maximum
                : remaining.compareTo(maximum) < 0 ? remaining : maximum;
    }

    private static PermissionProfile permission(PermissionProfile source, String executable, int bytes) {
        var processes = new ProcessPermission(
                Set.of(Path.of(executable).getFileName().toString()),
                false,
                source.processes().maxRunTime());
        var resources = new ResourceLimits(
                source.resources().memoryBytes(),
                Math.min(bytes, source.resources().outputBytes()),
                source.resources().childProcesses(),
                source.resources().openFiles());
        return new PermissionProfile(
                source.id(),
                source.version(),
                source.files(),
                new NetworkPermission(Set.of(), Set.of(), true),
                processes,
                source.tools(),
                resources);
    }

    private record Options(String cwd, int seconds, int bytes) {}

    private record Evidence(String snapshotDigest, Executable entry, List<Executable> executableEntries) {}

    private record Resolved(
            SandboxCommand command,
            PermissionProfile permission,
            SandboxRuntimeAccess access,
            String outputEncoding,
            Optional<CanonicalPayload> evidence)
            implements CodingResolvedCommand {
        @Override
        public void close() {
            // 系统安装没有托管制品租约；缓存和临时根沿用平台受保护目录的所有权。
        }
    }
}
