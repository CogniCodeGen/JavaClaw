package com.javaclaw.server.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 为受管 Git 调用准备跨平台可执行文件和无外部配置环境。 */
final class ManagedGitEnvironment {
    private ManagedGitEnvironment() {}

    static Path executable() {
        String configured = System.getProperty("javaclaw.git.executable", "").strip();
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        List<Path> candidates;
        if (os.contains("mac")) {
            candidates = List.of(
                    Path.of("/Applications/Xcode.app/Contents/Developer/usr/bin/git"),
                    Path.of("/Library/Developer/CommandLineTools/usr/bin/git"),
                    Path.of("/usr/bin/git"));
        } else if (os.contains("win")) {
            candidates = windowsCandidates();
        } else {
            candidates = List.of(Path.of("/usr/bin/git"));
        }
        return candidates.stream().filter(Files::isExecutable).findFirst().orElse(candidates.getLast());
    }

    static Map<String, String> variables(Path git, Path managedRoot) {
        Path emptyConfig = emptyGlobalConfig(managedRoot);
        Path emptyHooks = emptyHooksDirectory(managedRoot);
        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", emptyConfig.toString());
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_CONFIG_COUNT", "3");
        environment.put("GIT_CONFIG_KEY_0", "core.hooksPath");
        environment.put("GIT_CONFIG_VALUE_0", emptyHooks.toString());
        environment.put("GIT_CONFIG_KEY_1", "credential.helper");
        environment.put("GIT_CONFIG_VALUE_1", "");
        environment.put("GIT_CONFIG_KEY_2", "diff.external");
        environment.put("GIT_CONFIG_VALUE_2", "");
        environment.put("LC_ALL", "C");
        environment.put("PATH", git.getParent().toString());
        return Map.copyOf(environment);
    }

    static Path commonManagedRoot(Path first, Path second) {
        Path normalizedFirst = first.toAbsolutePath().normalize();
        Path normalizedSecond = second.toAbsolutePath().normalize();
        Path candidate = normalizedFirst.getParent();
        while (candidate != null && !normalizedSecond.startsWith(candidate)) {
            candidate = candidate.getParent();
        }
        if (candidate == null || candidate.getParent() == null) {
            throw new PersistenceException("Worktree 移动路径没有安全的公共根目录");
        }
        requireChild(candidate, normalizedFirst);
        requireChild(candidate, normalizedSecond);
        return candidate;
    }

    private static List<Path> windowsCandidates() {
        List<Path> candidates = new ArrayList<>();
        addWindowsGit(candidates, System.getenv("ProgramFiles"));
        addWindowsGit(candidates, System.getenv("ProgramFiles(x86)"));
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData).resolve("Programs/Git/cmd/git.exe"));
        }
        if (candidates.isEmpty()) {
            candidates.add(Path.of("C:\\Program Files\\Git\\cmd\\git.exe"));
        }
        return List.copyOf(candidates);
    }

    private static void addWindowsGit(List<Path> candidates, String programFiles) {
        if (programFiles != null && !programFiles.isBlank()) {
            candidates.add(Path.of(programFiles).resolve("Git/cmd/git.exe"));
        }
    }

    private static Path emptyGlobalConfig(Path managedRoot) {
        Path config = managedRoot.resolve(".gitconfig-empty").normalize();
        requireChild(managedRoot, config);
        try {
            if (Files.notExists(config)) {
                Files.createFile(config);
            }
            if (Files.isSymbolicLink(config) || !Files.isRegularFile(config) || Files.size(config) != 0) {
                throw new PersistenceException("Git 空配置文件已被替换");
            }
            return config;
        } catch (IOException failure) {
            throw new PersistenceException("无法创建 Git 空配置", failure);
        }
    }

    private static Path emptyHooksDirectory(Path managedRoot) {
        Path hooks = managedRoot.resolve("hooks-empty").normalize();
        requireChild(managedRoot, hooks);
        try {
            Files.createDirectories(hooks);
            if (Files.isSymbolicLink(hooks) || !Files.isDirectory(hooks, LinkOption.NOFOLLOW_LINKS)) {
                throw new PersistenceException("Git 空 hooks 目录已被替换");
            }
            try (var entries = Files.list(hooks)) {
                if (entries.findAny().isPresent()) {
                    throw new PersistenceException("Git 空 hooks 目录包含未授权文件");
                }
            }
            return hooks;
        } catch (IOException failure) {
            throw new PersistenceException("无法准备 Git 空 hooks 目录", failure);
        }
    }

    private static void requireChild(Path parent, Path child) {
        if (!child.startsWith(parent) || child.equals(parent)) {
            throw new PersistenceException("Git 受管环境路径越界");
        }
    }
}
