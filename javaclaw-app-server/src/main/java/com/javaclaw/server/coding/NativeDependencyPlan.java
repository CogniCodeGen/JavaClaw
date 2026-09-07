package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.builtin.contracts.CodingContracts;

/** 把依赖准备交给原生包管理器；本层只确定受治理的命令和输入证据，不解析依赖图。 */
record NativeDependencyPlan(
        CodingContracts.PackageManager manager,
        CodingContracts.CommandRun command,
        Map<String, Optional<String>> manifests,
        List<String> writableDirectories) {
    static List<String> manifests(CodingContracts.PackageManager manager) {
        return switch (manager) {
            case MAVEN -> List.of("pom.xml", ".mvn/maven.config", ".mvn/jvm.config", ".mvn/extensions.xml");
            case GRADLE ->
                List.of(
                        "build.gradle",
                        "build.gradle.kts",
                        "settings.gradle",
                        "settings.gradle.kts",
                        "gradle.properties",
                        "gradle.lockfile",
                        "gradle/libs.versions.toml");
            case NPM -> List.of("package.json", "package-lock.json", "npm-shrinkwrap.json", ".npmrc");
            case PNPM -> List.of("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", ".npmrc", ".pnpmfile.cjs");
            case PIP -> List.of("requirements.txt", "pyproject.toml", "setup.py", "setup.cfg");
        };
    }

    static List<String> argv(
            CodingContracts.DependenciesPrepare input,
            Map<String, Optional<String>> before,
            boolean scripts,
            Path cache,
            Path mavenSettings,
            String proxy) {
        requireScriptsPolicy(input.manager(), scripts);
        List<String> argv = new ArrayList<>();
        switch (input.manager()) {
            case MAVEN -> {
                argv.addAll(List.of(
                        "mvn", "--batch-mode", "--no-transfer-progress", "--settings", mavenSettings.toString()));
                argv.addAll(input.targets().isEmpty() ? List.of("dependency:go-offline") : input.targets());
            }
            case GRADLE -> {
                argv.add("gradle");
                argv.addAll(input.targets().isEmpty() ? List.of("dependencies") : input.targets());
            }
            case NPM -> {
                argv.addAll(List.of("npm", npmAction(before), "--no-audit", "--no-fund"));
            }
            case PNPM -> {
                argv.addAll(List.of(
                        "pnpm",
                        "install",
                        "--store-dir",
                        cache.resolve("pnpm-store").toString()));
                addLockPolicy(argv, before);
            }
            case PIP ->
                argv.addAll(List.of(
                        "python",
                        "-c",
                        CodingPipEvidence.SCRIPT,
                        cache.resolve("venv").toString(),
                        has(before, "requirements.txt") ? "requirements.txt" : ".",
                        proxy));
        }
        if (!scripts) {
            argv.add("--ignore-scripts");
        }
        return List.copyOf(argv);
    }

    private static String npmAction(Map<String, Optional<String>> before) {
        return has(before, "package-lock.json") || has(before, "npm-shrinkwrap.json") ? "ci" : "install";
    }

    private static void requireScriptsPolicy(CodingContracts.PackageManager manager, boolean scripts) {
        if (!scripts
                && manager != CodingContracts.PackageManager.NPM
                && manager != CodingContracts.PackageManager.PNPM) {
            throw new SecurityException("当前环境未允许包管理器项目脚本，不能执行该准备计划");
        }
    }

    private static void addLockPolicy(List<String> argv, Map<String, Optional<String>> before) {
        if (has(before, "pnpm-lock.yaml")) {
            argv.add("--frozen-lockfile");
        }
    }

    static String relative(String cwd, String file) {
        return cwd.isEmpty() || cwd.equals(".") ? file : cwd + "/" + file;
    }

    private static boolean has(Map<String, Optional<String>> manifests, String suffix) {
        return manifests.entrySet().stream()
                .anyMatch(entry ->
                        (entry.getKey().equals(suffix) || entry.getKey().endsWith("/" + suffix))
                                && entry.getValue().isPresent());
    }
}
