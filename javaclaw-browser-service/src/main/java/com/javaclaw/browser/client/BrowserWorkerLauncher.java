package com.javaclaw.browser.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.nativehost.sandbox.SandboxedWorkerCommand;

/** 为快照、登录和 OAuth 的每次启动分配独占 scratch；模板父目录永不授予目标 Worker。 */
final class BrowserWorkerLauncher implements BrowserWorkerClient.WorkerLauncher {
    private final SandboxedWorkerCommand template;
    private final CommandStarter starter;
    private final Object parentKey;

    BrowserWorkerLauncher(SandboxedWorkerCommand template, CommandStarter starter) {
        this.template = Objects.requireNonNull(template, "template");
        this.starter = Objects.requireNonNull(starter, "starter");
        try {
            parentKey = template.privateScratch().isPresent()
                    ? BrowserWorkerScratch.identity(
                            template.privateScratch().orElseThrow().root())
                    : null;
        } catch (IOException failure) {
            throw new UncheckedIOException("Browser Worker scratch template is unavailable", failure);
        }
    }

    @Override
    public Process start() throws IOException {
        if (template.privateScratch().isEmpty()) {
            return starter.start(template);
        }
        Path parent = template.privateScratch().orElseThrow().root();
        template.withPrivateScratch(parent);
        BrowserWorkerScratch scratch = BrowserWorkerScratch.create(parent, parentKey);
        Process process = null;
        try {
            process = starter.start(command(scratch.root(), parent));
            process.onExit().thenRunAsync(scratch::cleanupAfterExit);
            return process;
        } catch (IOException | RuntimeException failure) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                throw failure;
            }
            try {
                scratch.cleanup();
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private SandboxedWorkerCommand command(Path root, Path parent) throws IOException {
        if (!template.workingDirectory().equals(parent)) {
            throw new IOException("Browser Worker scratch template cwd must equal its private root");
        }
        String previous = "-Djava.io.tmpdir=" + parent;
        ArrayList<String> arguments = new ArrayList<>(template.argv());
        int temporaryArguments = 0;
        for (int index = 1; index < arguments.size(); index++) {
            if (arguments.get(index).startsWith("-Djava.io.tmpdir=")) {
                if (!arguments.get(index).equals(previous)) {
                    throw new IOException("Browser Worker Java temporary directory differs from its private root");
                }
                arguments.set(index, "-Djava.io.tmpdir=" + root);
                temporaryArguments++;
            }
        }
        if (temporaryArguments != 1) {
            throw new IOException("Browser Worker must declare exactly one private Java temporary directory");
        }
        LinkedHashMap<String, String> environment = new LinkedHashMap<>(template.environment());
        if (!parent.toString().equals(environment.get("TMPDIR"))) {
            throw new IOException("Browser Worker TMPDIR differs from its private root");
        }
        environment.put("TMPDIR", root.toString());
        return new SandboxedWorkerCommand(
                        template.id(),
                        arguments,
                        root,
                        environment,
                        template.readRoots(),
                        List.of(root),
                        template.executableRoots(),
                        template.lifetime(),
                        template.limits(),
                        Optional.empty())
                .withPrivateScratch(root);
    }

    @FunctionalInterface
    interface CommandStarter {
        Process start(SandboxedWorkerCommand command) throws IOException;
    }
}
