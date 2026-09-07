package com.javaclaw.server.coding;

import java.io.File;
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

import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;

/** 只解析目录中的固定工具链入口，绝不搜索宿主 PATH 或从模型参数扩充权限根。 */
final class ManagedCommandResolver {
    private final CodingToolchainPort toolchains;
    private final Path managedRoot;
    private final Clock clock;
    private final java.util.function.BiConsumer<com.javaclaw.api.TurnId, List<ToolchainKind>> compatibility;

    ManagedCommandResolver(
            CodingToolchainPort toolchains,
            Path dataRoot,
            Clock clock,
            java.util.function.BiConsumer<com.javaclaw.api.TurnId, List<ToolchainKind>> compatibility) {
        this.toolchains = toolchains;
        this.compatibility = compatibility;
        managedRoot = dataRoot.resolve("coding");
        this.clock = clock;
    }

    Resolved resolve(CodingInvocation invocation, CodingContracts.CommandRun input, SandboxMode mode) throws Exception {
        String name = input.argv().getFirst();
        requirePermission(invocation.permission(), name, mode);
        List<ToolchainKind> required = required(name);
        compatibility.accept(invocation.turn().id(), required);
        var references = required.stream()
                .map(kind -> invocation.environment().spec().toolchains().stream()
                        .filter(reference -> reference.kind() == kind)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("TOOLCHAIN_MISSING: " + kind)))
                .toList();
        CodingToolchainPort.Lease lease = toolchains.acquire(invocation.workspaceId(), references);
        try {
            return command(invocation, input, mode, lease);
        } catch (Exception failure) {
            lease.close();
            throw failure;
        }
    }

    private Resolved command(
            CodingInvocation invocation,
            CodingContracts.CommandRun input,
            SandboxMode mode,
            CodingToolchainPort.Lease lease)
            throws Exception {
        var installed = lease.installations();
        ArrayList<String> argv = new ArrayList<>(entry(input.argv().getFirst(), installed));
        argv.addAll(input.argv().subList(1, input.argv().size()));
        Path root = invocation.turn().executionRoot();
        Path cwd = root.resolve(input.workingDirectory()).normalize();
        if (!cwd.startsWith(root)) {
            throw new SecurityException("命令目录超出执行根");
        }
        // bindTool 已校验全部 data-v6；这里额外确认命令不能改写缓存和控制目录的管理祖先。
        CodingDataBoundary.requireOutside(root, invocation.permission(), managedRoot);
        var directories = new ManagedCommandDirectories(managedRoot.getParent())
                .prepare(cacheRoot(invocation), invocation.turn().id());
        Path cache = directories.cache();
        Path temporary = directories.temporary();
        argv = new ArrayList<>(CodingPythonCommand.select(input.argv().getFirst(), argv, cache));
        Optional<MavenProjectLaunch.Evidence> maven = Optional.empty();
        if (input.argv().getFirst().equals("mvn")) {
            var project = MavenProjectLaunch.resolve(invocation, cwd, input.argv());
            argv = mavenArguments(argv, project, installed, cache, temporary);
            maven = Optional.of(project.evidence());
        }
        addJavaOptions(argv, installed, cache, temporary);
        Map<String, String> environment = environment(installed, cache, temporary);
        SandboxCommand command = new SandboxCommand(
                invocation.id(),
                argv,
                cwd,
                environment,
                new byte[0],
                mode,
                duration(invocation, input.timeoutSeconds()));
        List<Path> reads = installed.values().stream()
                .map(CodingToolchainPort.InstalledArtifact::root)
                .distinct()
                .toList();
        List<Path> executables = new ArrayList<>(reads);
        executables.add(root);
        executables.add(cache);
        executables.add(temporary);
        addShellRuntime(executables);
        List<Path> allReads = new ArrayList<>(reads);
        for (Path executable : executables) {
            if (!executable.startsWith(root)
                    && !executable.startsWith(cache)
                    && !executable.startsWith(temporary)
                    && reads.stream().noneMatch(executable::startsWith)) {
                allReads.add(executable.getParent());
            }
        }
        var access =
                new SandboxRuntimeAccess(allReads.stream().distinct().toList(), List.of(cache, temporary), executables);
        return new Resolved(
                command,
                processPermission(invocation.permission(), argv.getFirst(), input.maxOutputBytes()),
                access,
                lease,
                cache,
                maven);
    }

    private static void addJavaOptions(
            List<String> argv,
            Map<ToolchainKind, CodingToolchainPort.InstalledArtifact> installed,
            Path cache,
            Path temporary) {
        if (argv.getFirst().equals(executableOrEmpty(installed, ToolchainKind.JDK, "java"))) {
            argv.add(1, "-Duser.home=" + cache);
            argv.add(1, "-Djava.io.tmpdir=" + temporary);
            argv.add(1, "-Djava.net.preferIPv4Stack=true");
        }
    }

    private static ArrayList<String> mavenArguments(
            List<String> argv,
            MavenProjectLaunch.Result project,
            Map<ToolchainKind, CodingToolchainPort.InstalledArtifact> installed,
            Path cache,
            Path temporary) {
        var governed = new LinkedHashMap<String, String>();
        for (String argument : argv) {
            if (argument.startsWith("-Dmaven.home=") || argument.startsWith("-Dclassworlds.conf=")) {
                int separator = argument.indexOf('=');
                governed.putIfAbsent(argument.substring(2, separator), argument.substring(separator + 1));
            }
        }
        governed.put(
                "java.home",
                executable(installed, ToolchainKind.JDK, "java")
                        .getParent()
                        .getParent()
                        .toString());
        governed.put("user.home", cache.toString());
        governed.put("java.io.tmpdir", temporary.toString());
        governed.put("java.net.preferIPv4Stack", "true");
        governed.put(
                "maven.multiModuleProjectDirectory", project.baseDirectory().toString());
        MavenJvmArguments.requireProperties(project.jvmArguments(), governed);
        MavenJvmArguments.requireProperties(argv, governed);
        var result = new ArrayList<>(MavenJvmArguments.prepend(argv, project.jvmArguments()));
        result.add(1, "-Dmaven.multiModuleProjectDirectory=" + project.baseDirectory());
        return result;
    }

    Path cacheRoot(CodingInvocation invocation) {
        String environment = new com.javaclaw.protocol.CanonicalJson()
                .encode(invocation.environment())
                .sha256();
        return managedRoot
                .resolve("caches")
                .resolve(invocation.workspaceId().toString())
                .resolve(CodingToolchainCatalog.platform() + "-" + CodingToolchainCatalog.architecture())
                .resolve(environment);
    }

    private static List<ToolchainKind> required(String name) {
        return switch (name) {
            case "java", "javac" -> List.of(ToolchainKind.JDK);
            case "mvn" -> List.of(ToolchainKind.JDK, ToolchainKind.MAVEN);
            case "gradle" -> List.of(ToolchainKind.JDK, ToolchainKind.GRADLE);
            case "node" -> List.of(ToolchainKind.NODE);
            case "npm" -> List.of(ToolchainKind.NODE, ToolchainKind.NPM);
            case "pnpm" -> List.of(ToolchainKind.NODE, ToolchainKind.PNPM);
            case "python", "python3" -> List.of(ToolchainKind.PYTHON);
            case "pip" -> List.of(ToolchainKind.PYTHON, ToolchainKind.PIP);
            default -> throw new IllegalArgumentException("UNREGISTERED_EXECUTABLE: " + name);
        };
    }

    private static List<String> entry(String name, Map<ToolchainKind, CodingToolchainPort.InstalledArtifact> installed)
            throws Exception {
        return switch (name) {
            case "java", "javac" ->
                List.of(executable(installed, ToolchainKind.JDK, name).toString());
            case "node" ->
                List.of(executable(installed, ToolchainKind.NODE, "node").toString());
            case "python", "python3" ->
                List.of(executable(installed, ToolchainKind.PYTHON, "python").toString());
            case "pip" ->
                List.of(executable(installed, ToolchainKind.PYTHON, "python").toString(), "-m", "pip");
            case "npm", "pnpm" ->
                List.of(
                        executable(installed, ToolchainKind.NODE, "node").toString(),
                        executable(installed, name.equals("npm") ? ToolchainKind.NPM : ToolchainKind.PNPM, name)
                                .toString());
            case "mvn" -> maven(installed);
            case "gradle" -> gradle(installed);
            default -> throw new IllegalArgumentException("未知工具链入口");
        };
    }

    private static List<String> maven(Map<ToolchainKind, CodingToolchainPort.InstalledArtifact> installed)
            throws Exception {
        Path home =
                executable(installed, ToolchainKind.MAVEN, "mvn").getParent().getParent();
        Path boot = jar(home.resolve("boot"), "plexus-classworlds-");
        return List.of(
                executable(installed, ToolchainKind.JDK, "java").toString(),
                "-Dmaven.home=" + home,
                "-Dclassworlds.conf=" + home.resolve("bin/m2.conf"),
                "-classpath",
                boot.toString(),
                "org.codehaus.plexus.classworlds.launcher.Launcher");
    }

    private static List<String> gradle(Map<ToolchainKind, CodingToolchainPort.InstalledArtifact> installed)
            throws Exception {
        Path home = executable(installed, ToolchainKind.GRADLE, "gradle")
                .getParent()
                .getParent();
        Path main;
        try {
            main = jar(home.resolve("lib"), "gradle-gradle-cli-main-");
        } catch (IllegalStateException oldDistribution) {
            main = jar(home.resolve("lib"), "gradle-launcher-");
        }
        return List.of(
                executable(installed, ToolchainKind.JDK, "java").toString(),
                "-Dorg.gradle.appname=gradle",
                "-classpath",
                main.toString(),
                "org.gradle.launcher.GradleMain",
                "--no-daemon");
    }

    private static Path jar(Path directory, String prefix) throws Exception {
        try (var files = Files.list(directory)) {
            List<Path> candidates = files.filter(
                            path -> path.getFileName().toString().startsWith(prefix)
                                    && path.toString().endsWith(".jar"))
                    .toList();
            if (candidates.size() != 1) {
                throw new IllegalStateException("托管工具链启动组件不唯一或缺失");
            }
            return candidates.getFirst();
        }
    }

    private static Path executable(
            Map<ToolchainKind, CodingToolchainPort.InstalledArtifact> installed, ToolchainKind kind, String name) {
        var installation = installed.get(kind);
        String relative = installation.artifact().executablePaths().get(name);
        if (relative == null) {
            throw new IllegalStateException("工具链未声明固定入口: " + name);
        }
        Path resolved = installation.root().resolve(relative).normalize();
        if (!resolved.startsWith(installation.root())) {
            throw new SecurityException("工具链入口越界");
        }
        return resolved;
    }

    private static void requirePermission(PermissionProfile permission, String name, SandboxMode mode) {
        if (!permission.processes().executables().contains(name)
                || (mode == SandboxMode.PTY && !permission.processes().allowPty())) {
            throw new SecurityException("进程权限未授权固定入口: " + name);
        }
    }

    private Duration duration(CodingInvocation invocation, int seconds) {
        Duration remaining = Duration.between(
                clock.instant(),
                invocation.turn().createdAt().plus(invocation.turn().budget().wallTime()));
        Duration timeout = Duration.ofSeconds(seconds);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new IllegalStateException("TURN_DEADLINE_EXCEEDED: Turn 墙钟预算已耗尽");
        }
        timeout = timeout.compareTo(remaining) < 0 ? timeout : remaining;
        Duration maximum = invocation.permission().processes().maxRunTime();
        return timeout.compareTo(maximum) < 0 ? timeout : maximum;
    }

    private static PermissionProfile processPermission(PermissionProfile approved, String actual, int outputBytes) {
        // 仅在逻辑入口已明确授权后，转换到固定启动器的 basename；其余权限保持或收窄。
        var processes = new ProcessPermission(
                Set.of(Path.of(actual).getFileName().toString()),
                approved.processes().allowPty(),
                approved.processes().maxRunTime());
        var resources = new ResourceLimits(
                approved.resources().memoryBytes(),
                Math.min(approved.resources().outputBytes(), outputBytes),
                approved.resources().childProcesses(),
                approved.resources().openFiles());
        return new PermissionProfile(
                approved.id(),
                approved.version(),
                approved.files(),
                new NetworkPermission(Set.of(), Set.of(), true),
                processes,
                approved.tools(),
                resources);
    }

    private static Map<String, String> environment(
            Map<ToolchainKind, CodingToolchainPort.InstalledArtifact> installed, Path cache, Path temporary) {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        List<String> path = new ArrayList<>();
        installed
                .values()
                .forEach(installation -> installation
                        .artifact()
                        .executablePaths()
                        .values()
                        .forEach(relative -> path.add(installation
                                .root()
                                .resolve(relative)
                                .getParent()
                                .toString())));
        path.add(
                CodingToolchainCatalog.platform().equals("windows")
                        ? windowsSystem().toString()
                        : "/usr/bin:/bin");
        environment.put(
                "PATH", String.join(File.pathSeparator, path.stream().distinct().toList()));
        environment.put("HOME", cache.toString());
        environment.put("USERPROFILE", cache.toString());
        environment.put("TMPDIR", temporary.toString());
        environment.put("TMP", temporary.toString());
        environment.put("TEMP", temporary.toString());
        environment.put("GRADLE_USER_HOME", cache.resolve("gradle").toString());
        environment.put("npm_config_cache", cache.resolve("npm").toString());
        // npm/pnpm 会把各级祖先的 node_modules/.bin 加入 PATH；固定已授权 shell，避免在 Workspace 外搜索入口。
        environment.put(
                "npm_config_script_shell",
                CodingToolchainCatalog.platform().equals("windows")
                        ? windowsSystem().resolve("cmd.exe").toString()
                        : "/bin/sh");
        environment.put("PNPM_HOME", cache.resolve("pnpm").toString());
        environment.put("PIP_CACHE_DIR", cache.resolve("pip").toString());
        environment.put("PIP_DISABLE_PIP_VERSION_CHECK", "1");
        environment.put("PYTHONNOUSERSITE", "1");
        environment.put("LANG", "en_US.UTF-8");
        if (installed.containsKey(ToolchainKind.JDK)) {
            environment.put(
                    "JAVA_HOME",
                    executable(installed, ToolchainKind.JDK, "java")
                            .getParent()
                            .getParent()
                            .toString());
        }
        if (CodingToolchainCatalog.platform().equals("windows")) {
            environment.put("SystemRoot", windowsSystem().getParent().toString());
            environment.put("COMSPEC", windowsSystem().resolve("cmd.exe").toString());
        }
        return Map.copyOf(environment);
    }

    private static String executableOrEmpty(
            Map<ToolchainKind, CodingToolchainPort.InstalledArtifact> installed, ToolchainKind kind, String name) {
        return installed.containsKey(kind) ? executable(installed, kind, name).toString() : "";
    }

    private static void addShellRuntime(List<Path> executables) {
        List<Path> candidates = CodingToolchainCatalog.platform().equals("windows")
                ? List.of(windowsSystem().resolve("cmd.exe"))
                : List.of(
                        Path.of("/bin/sh"),
                        Path.of("/usr/bin/env"),
                        Path.of("/usr/bin/uname"),
                        Path.of("/usr/bin/dirname"),
                        Path.of("/usr/bin/basename"),
                        Path.of("/bin/sed"),
                        Path.of("/usr/bin/tr"));
        candidates.stream().filter(Files::isRegularFile).forEach(executables::add);
        if (CodingToolchainCatalog.platform().equals("macos")) {
            // macOS 的 /bin/sh 是由系统 select/sh 选择 bash/zsh 的固定入口，子进程仍继承同一沙箱。
            List.of(Path.of("/bin/bash"), Path.of("/bin/zsh")).stream()
                    .filter(Files::isRegularFile)
                    .forEach(executables::add);
        }
    }

    private static Path windowsSystem() {
        // SystemRoot 只取服务进程启动环境，不能由模型或项目覆盖。
        return Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"))
                .resolve("System32");
    }

    record Resolved(
            SandboxCommand command,
            PermissionProfile permission,
            SandboxRuntimeAccess access,
            CodingToolchainPort.Lease lease,
            Path cacheRoot,
            Optional<MavenProjectLaunch.Evidence> maven)
            implements AutoCloseable {
        Resolved(
                SandboxCommand command,
                PermissionProfile permission,
                SandboxRuntimeAccess access,
                CodingToolchainPort.Lease lease,
                Path cacheRoot) {
            this(command, permission, access, lease, cacheRoot, Optional.empty());
        }

        @Override
        public void close() {
            lease.close();
        }
    }
}
