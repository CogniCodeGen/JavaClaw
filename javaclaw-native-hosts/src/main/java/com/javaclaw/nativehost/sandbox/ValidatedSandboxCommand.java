package com.javaclaw.nativehost.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxMode;

/** 已解析真实路径且可安全交给平台 backend 的内部命令。 */
record ValidatedSandboxCommand(
        String id,
        List<String> argv,
        Path executable,
        Path workingDirectory,
        Map<String, String> environment,
        byte[] standardInput,
        SandboxMode mode,
        Duration timeout,
        ResourceLimits limits,
        List<Path> readRoots,
        List<Path> writeRoots,
        List<Path> executableRoots,
        boolean allowDelete,
        Optional<Path> terminal) {
    ValidatedSandboxCommand {
        argv = List.copyOf(argv);
        environment = Map.copyOf(environment);
        standardInput = standardInput.clone();
        java.util.Objects.requireNonNull(mode, "mode");
        readRoots = List.copyOf(readRoots);
        writeRoots = List.copyOf(writeRoots);
        executableRoots = List.copyOf(executableRoots);
        terminal = java.util.Objects.requireNonNull(terminal, "terminal");
    }

    @Override
    public byte[] standardInput() {
        return standardInput.clone();
    }

    ValidatedSandboxCommand withTerminal(Path path) {
        return new ValidatedSandboxCommand(
                id,
                argv,
                executable,
                workingDirectory,
                environment,
                standardInput,
                mode,
                timeout,
                limits,
                readRoots,
                writeRoots,
                executableRoots,
                allowDelete,
                Optional.of(path.toAbsolutePath().normalize()));
    }

    ValidatedSandboxCommand withExecutableRoots(List<Path> roots) {
        return new ValidatedSandboxCommand(
                id,
                argv,
                executable,
                workingDirectory,
                environment,
                standardInput,
                mode,
                timeout,
                limits,
                readRoots,
                writeRoots,
                roots,
                allowDelete,
                terminal);
    }
}
