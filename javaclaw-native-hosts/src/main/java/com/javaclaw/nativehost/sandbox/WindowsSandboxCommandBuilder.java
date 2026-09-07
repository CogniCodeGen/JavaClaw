package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.javaclaw.api.SandboxMode;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/** 使用受信任 Java helper 启动 Windows AppContainer；helper 句柄关闭即终止目标 Job。 */
final class WindowsSandboxCommandBuilder implements SandboxCommandBuilder {
    private static final Duration FALLBACK_TIMEOUT_GRACE = Duration.ofSeconds(5);

    private final Path javaExecutable;

    WindowsSandboxCommandBuilder() {
        this(Path.of(System.getProperty("java.home"), "bin", "java.exe"));
    }

    WindowsSandboxCommandBuilder(Path javaExecutable) {
        this.javaExecutable = javaExecutable.toAbsolutePath().normalize();
    }

    @Override
    public SandboxLaunchPlan build(ValidatedSandboxCommand command) {
        return build(command, null);
    }

    SandboxLaunchPlan build(ValidatedSandboxCommand command, Path controlDirectory) {
        if (command.mode() != SandboxMode.BATCH) {
            throw new IllegalArgumentException("Windows batch builder cannot launch a PTY command");
        }
        if (!Files.isExecutable(javaExecutable)) {
            throw new UnsupportedOperationException("Windows Sandbox helper Java executable is unavailable");
        }
        return new SandboxLaunchPlan(name(), helperCommand(command, controlDirectory), command.environment(), 1, true);
    }

    @Override
    public String name() {
        return "windows-appcontainer-job";
    }

    private List<String> helperCommand(ValidatedSandboxCommand command, Path controlDirectory) {
        ArrayList<String> result = new ArrayList<>();
        result.add(javaExecutable.toString());
        result.add("--enable-native-access=ALL-UNNAMED");
        result.add("-XX:-UsePerfData");
        result.add("-cp");
        result.add(System.getProperty("java.class.path"));
        result.add(WindowsSandboxExecMain.class.getName());
        if (controlDirectory != null) {
            result.add(WindowsHelperControl.OPTION);
            result.add(controlDirectory.toString());
        }
        addRequest(result, command);
        return List.copyOf(result);
    }

    private static void addRequest(List<String> result, ValidatedSandboxCommand command) {
        result.add(command.workingDirectory().toString());
        result.add(Boolean.toString(command.allowDelete()));
        result.add(Long.toString(Math.min(
                Duration.ofHours(24).toMillis(),
                command.timeout().plus(FALLBACK_TIMEOUT_GRACE).toMillis())));
        result.add(Long.toString(command.limits().memoryBytes()));
        result.add(Long.toString(command.limits().outputBytes()));
        result.add(Integer.toString(command.limits().childProcesses()));
        result.add(Integer.toString(command.limits().openFiles()));
        result.add(Integer.toString(command.readRoots().size()));
        result.add(Integer.toString(command.writeRoots().size()));
        command.readRoots().forEach(path -> result.add(path.toString()));
        command.writeRoots().forEach(path -> result.add(path.toString()));
        addNetwork(result, command.networkAccess());
        result.add("--");
        result.addAll(command.argv());
    }

    private static void addNetwork(List<String> result, SandboxNetworkAccess network) {
        if (network.mode() == SandboxNetworkAccess.Mode.PROXY_ONLY) {
            result.add("--network-proxy-v1");
            result.add(network.grantId().orElseThrow());
            result.add(Integer.toString(network.proxyEndpoint().orElseThrow().getPort()));
            result.add(network.controlDirectory().orElseThrow().toString());
        }
    }
}
