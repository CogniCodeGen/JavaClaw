package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 构造受信任 helper argv；helper 在 OS Sandbox 外先施加可继承的资源边界，再 exec 隔离 backend。 */
final class SandboxHelperCommand {
    private SandboxHelperCommand() {}

    static List<String> wrap(ValidatedSandboxCommand command, List<String> isolatedCommand) {
        Class<?> helper =
                command.mode() == com.javaclaw.api.SandboxMode.PTY ? SandboxPtyExecMain.class : SandboxExecMain.class;
        ArrayList<String> result;
        try {
            result = new ArrayList<>(SandboxJavaRuntime.forWorker(helper).command(helper, List.of(), 32));
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException("cannot resolve Sandbox helper runtime", failure);
        }
        result.add(Long.toString(command.timeout().toMillis()));
        result.add(Long.toString(command.limits().memoryBytes()));
        result.add(Long.toString(command.limits().outputBytes()));
        result.add(Integer.toString(command.limits().childProcesses()));
        result.add(Integer.toString(command.limits().openFiles()));
        result.add("--");
        result.addAll(List.copyOf(isolatedCommand));
        return List.copyOf(result);
    }

    static Path javaExecutable() {
        String name = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("windows")
                ? "java.exe"
                : "java";
        Path executable;
        try {
            executable = Path.of(System.getProperty("java.home"), "bin", name).toRealPath();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot resolve Sandbox helper Java executable", failure);
        }
        if (!Files.isExecutable(executable)) {
            throw new IllegalStateException("Sandbox helper Java executable is unavailable");
        }
        return executable;
    }
}
