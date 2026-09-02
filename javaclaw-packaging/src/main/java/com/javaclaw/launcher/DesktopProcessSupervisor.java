package com.javaclaw.launcher;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import com.javaclaw.launcher.tray.MainWindowControl;

/** 启动单个 SDK-only Desktop 子进程；子进程退出不会停止 App Server。 */
final class DesktopProcessSupervisor implements MainWindowControl {
    private final RuntimeLayout layout;
    private final String transportProperty;
    private final String[] arguments;
    private final ProcessStarter starter;
    private ChildProcess child;

    DesktopProcessSupervisor(RuntimeLayout layout, String transportProperty, String[] arguments) {
        this(layout, transportProperty, arguments, DesktopProcessSupervisor::startProcess);
    }

    DesktopProcessSupervisor(
            RuntimeLayout layout, String transportProperty, String[] arguments, ProcessStarter starter) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.transportProperty = text(transportProperty, "transportProperty");
        this.arguments = Objects.requireNonNull(arguments, "arguments").clone();
        this.starter = Objects.requireNonNull(starter, "starter");
    }

    @Override
    public synchronized boolean running() {
        return child != null && child.alive();
    }

    @Override
    public synchronized void open(Runnable onExit) throws IOException {
        Runnable callback = Objects.requireNonNull(onExit, "onExit");
        if (running()) {
            return;
        }
        ChildProcess started =
                starter.start(JavaClawLauncher.desktopCommand(layout, transportProperty, arguments, true));
        child = started;
        Thread.ofVirtual().name("javaclaw-desktop-watch").start(() -> await(started, callback));
    }

    private void await(ChildProcess watched, Runnable onExit) {
        try {
            watched.awaitExit();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                if (child == watched) {
                    child = null;
                }
            }
            onExit.run();
        }
    }

    private static ChildProcess startProcess(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).inheritIO().start();
        return new ChildProcess() {
            @Override
            public boolean alive() {
                return process.isAlive();
            }

            @Override
            public void awaitExit() throws InterruptedException {
                process.waitFor();
            }
        };
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    @FunctionalInterface
    interface ProcessStarter {
        ChildProcess start(List<String> command) throws IOException;
    }

    interface ChildProcess {
        boolean alive();

        void awaitExit() throws InterruptedException;
    }
}
