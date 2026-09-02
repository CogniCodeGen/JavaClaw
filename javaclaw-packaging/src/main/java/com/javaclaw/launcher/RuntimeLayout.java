package com.javaclaw.launcher;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 已安装发行包中经过验证的只读路径布局。 */
record RuntimeLayout(Path root, Path javaExecutable, Path libraryDirectory, Optional<Path> serviceLauncher) {
    RuntimeLayout {
        Path checkedRoot = directory(absolute(root, "root"), "distribution root");
        root = checkedRoot;
        javaExecutable = regular(absolute(javaExecutable, "javaExecutable"), "runtime java");
        libraryDirectory = inside(
                checkedRoot,
                directory(absolute(libraryDirectory, "libraryDirectory"), "library directory"),
                "library directory");
        serviceLauncher = Objects.requireNonNull(serviceLauncher, "serviceLauncher")
                .map(path -> inside(
                        checkedRoot,
                        regular(absolute(path, "serviceLauncher"), "service launcher"),
                        "service launcher"));
        WorkerImageLayout.requireMainLibraryIsolation(libraryDirectory);
    }

    static RuntimeLayout fromSystemProperties() {
        String configured = System.getProperty("javaclaw.program.dir", "").strip();
        if (configured.isEmpty()) {
            throw new IllegalStateException("必须通过 -Djavaclaw.program.dir 指定发行包绝对目录");
        }
        Path root = directory(Path.of(configured).toAbsolutePath().normalize(), "distribution root");
        String javaName = isWindows() ? "java.exe" : "java";
        String launcherName = isWindows() ? "javaclaw-service.cmd" : "javaclaw-service";
        boolean expanded = Files.isDirectory(root.resolve("runtime")) && Files.isDirectory(root.resolve("lib"));
        Path java = expanded
                ? root.resolve("runtime/bin").resolve(javaName)
                : Path.of(System.getProperty("java.home"), "bin", javaName);
        Path library = expanded ? root.resolve("lib") : root;
        Path launcher = root.resolve("bin").resolve(launcherName);
        Optional<Path> service = Files.isRegularFile(launcher) ? Optional.of(launcher) : Optional.empty();
        return new RuntimeLayout(root, java, library, service);
    }

    String classpath() {
        return libraryDirectory.resolve("*").toString();
    }

    List<String> appServerProperties() {
        ArrayList<String> properties = new ArrayList<>();
        serviceLauncher.ifPresent(path -> properties.add("-Djavaclaw.server.launcher=" + path));
        addWorker(properties, "skill", "javaclaw.skill.worker.image-root");
        addWorker(properties, "knowledge", "javaclaw.knowledge.worker.image-root");
        addWorker(properties, "browser", "javaclaw.browser.worker.image-root");
        return List.copyOf(properties);
    }

    private void addWorker(List<String> properties, String type, String property) {
        WorkerImageLayout.discover(root, type).ifPresent(path -> properties.add("-D" + property + "=" + path));
    }

    private static Path absolute(Path value, String name) {
        Path path = Objects.requireNonNull(value, name).normalize();
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be absolute");
        }
        return path;
    }

    private static Path regular(Path path, String name) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(name + " does not exist or is a symbolic link: " + path);
            }
            return path.toRealPath();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(name + " cannot be resolved: " + path, failure);
        }
    }

    private static Path directory(Path path, String name) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(name + " does not exist or is a symbolic link: " + path);
            }
            return path.toRealPath();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(name + " cannot be resolved: " + path, failure);
        }
    }

    private static Path inside(Path root, Path path, String name) {
        if (!path.startsWith(root)) {
            throw new IllegalStateException(name + " escapes the distribution root: " + path);
        }
        if (!Files.exists(path)) {
            throw new IllegalStateException(name + " does not exist: " + path);
        }
        return path;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
    }
}
