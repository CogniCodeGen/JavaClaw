package com.javaclaw.agent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.TurnConfig;

/** Codex 兼容的 AGENTS.md 分层发现器；按 Thread 环境缓存，不监听普通文件变化。 */
public final class AgentsInstructionResolver implements AgentsInstructionUseCases {
    private static final List<String> STANDARD_CANDIDATES = List.of("AGENTS.override.md", "AGENTS.md");

    private final Path globalRoot;
    private final Supplier<AgentsInstructionSettings> settings;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 固定 JavaClaw 配置根和设置快照来源；不读取 CODEX_HOME，也不创建缺失目录。 */
    public AgentsInstructionResolver(Path globalRoot, Supplier<AgentsInstructionSettings> settings) {
        this.globalRoot = Objects.requireNonNull(globalRoot, "globalRoot")
                .toAbsolutePath()
                .normalize();
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * 解析或复用指定 Thread 的不可变链；只有工作目录、可读根或发现设置变化才刷新缓存。
     *
     * @throws IllegalArgumentException 路径越界、候选文件不可读或配置无效
     */
    public AgentsInstructionResolution resolve(AgentThread thread, TurnConfig config) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(config, "config");
        AgentsInstructionSettings snapshot = Objects.requireNonNull(settings.get(), "AGENTS.md settings");
        CacheKey key;
        try {
            key = key(thread.workingDirectory(), config.sandboxPolicy().readableRoots(), snapshot);
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot resolve AGENTS.md environment", failure);
        }
        CacheEntry previous = cache.get(thread.id().value());
        if (previous != null && previous.key().equals(key)) {
            return previous.resolution();
        }
        AgentsInstructionResolution resolved = resolveUncached(
                thread.workingDirectory(), config.sandboxPolicy().readableRoots(), snapshot, previous);
        cache.put(thread.id().value(), new CacheEntry(key, resolved));
        return resolved;
    }

    @Override
    public AgentsInstructionResolution inspect(Path workingDirectory, Set<Path> readableRoots) {
        return resolveUncached(
                workingDirectory, readableRoots, Objects.requireNonNull(settings.get(), "AGENTS.md settings"), null);
    }

    private AgentsInstructionResolution resolveUncached(
            Path requested, Set<Path> requestedRoots, AgentsInstructionSettings snapshot, CacheEntry previous) {
        try {
            Path workingDirectory = requireDirectory(requested);
            List<Path> roots = allowedRoots(workingDirectory, requestedRoots);
            Path boundary = roots.stream()
                    .filter(workingDirectory::startsWith)
                    .max(Comparator.comparingInt(Path::getNameCount))
                    .orElseThrow(() -> new IllegalArgumentException("working directory is outside readable roots"));
            List<AgentsInstructionResolution.Source> sources = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            Path allowedGlobalRoot = Files.exists(globalRoot) ? globalRoot.toRealPath() : globalRoot;
            readFirst(
                            globalRoot,
                            STANDARD_CANDIDATES,
                            "global",
                            List.of(allowedGlobalRoot),
                            Integer.MAX_VALUE,
                            warnings)
                    .ifPresent(sources::add);
            List<String> candidates = new ArrayList<>(STANDARD_CANDIDATES);
            candidates.addAll(snapshot.projectDocFallbackFilenames());
            int remaining = snapshot.projectDocMaxBytes();
            long projectBytes = 0;
            for (Path directory : projectDirectories(workingDirectory, boundary, snapshot.projectRootMarkers())) {
                if (remaining == 0) {
                    break;
                }
                var source = readFirst(directory, candidates, "project", roots, remaining, warnings);
                if (source.isEmpty()) {
                    continue;
                }
                AgentsInstructionResolution.Source selected = source.get();
                sources.add(selected);
                projectBytes += selected.bytes();
                remaining -= Math.toIntExact(selected.bytes());
                if (selected.truncated()) {
                    break;
                }
            }
            AgentsInstructionResolution current =
                    new AgentsInstructionResolution(workingDirectory, sources, warnings, projectBytes);
            if (previous != null && !previous.resolution().fingerprint().equals(current.fingerprint())) {
                List<String> changed = new ArrayList<>(current.warnings());
                changed.add(
                        current.sources().isEmpty()
                                ? AgentsInstructionResolution.REMOVAL_NOTICE
                                : AgentsInstructionResolution.REPLACEMENT_NOTICE);
                current = new AgentsInstructionResolution(
                        current.workingDirectory(), current.sources(), changed, current.totalProjectBytes());
            }
            return current;
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot resolve AGENTS.md instructions", failure);
        }
    }

    private java.util.Optional<AgentsInstructionResolution.Source> readFirst(
            Path directory,
            List<String> candidates,
            String scope,
            List<Path> allowedRoots,
            int remaining,
            List<String> warnings)
            throws IOException {
        for (String name : candidates) {
            Path candidate = directory.resolve(name).toAbsolutePath().normalize();
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path actual = candidate.toRealPath();
            if (allowedRoots.stream().noneMatch(actual::startsWith)) {
                throw new IllegalArgumentException("AGENTS.md symlink target is outside readable roots: " + candidate);
            }
            if (!Files.isRegularFile(actual) || !Files.isReadable(actual)) {
                throw new IllegalArgumentException("AGENTS.md candidate is not a readable regular file: " + candidate);
            }
            byte[] original = Files.readAllBytes(actual);
            String decoded = new String(original, StandardCharsets.UTF_8);
            if (decoded.isBlank()) {
                continue;
            }
            boolean truncated = original.length > remaining;
            byte[] included = truncated ? java.util.Arrays.copyOf(original, remaining) : original;
            String content = new String(included, StandardCharsets.UTF_8);
            if (truncated) {
                warnings.add("项目 AGENTS.md 指令超过总预算，已在 " + actual + " 截断到剩余 " + remaining + " 字节。");
            }
            return java.util.Optional.of(new AgentsInstructionResolution.Source(
                    scope, actual, included.length, PromptHashes.sha256(included), truncated, content));
        }
        return java.util.Optional.empty();
    }

    private static List<Path> projectDirectories(Path workingDirectory, Path boundary, List<String> markers) {
        Path root = null;
        for (Path current = workingDirectory;
                current != null && current.startsWith(boundary);
                current = current.getParent()) {
            boolean marked = false;
            for (String marker : markers) {
                if (Files.exists(current.resolve(marker))) {
                    marked = true;
                    break;
                }
            }
            if (marked) {
                root = current;
                break;
            }
            if (current.equals(boundary)) {
                break;
            }
        }
        if (root == null) {
            return List.of(workingDirectory);
        }
        ArrayList<Path> result = new ArrayList<>();
        for (Path current = workingDirectory;
                current != null && current.startsWith(root);
                current = current.getParent()) {
            result.add(current);
            if (current.equals(root)) {
                break;
            }
        }
        return List.copyOf(result.reversed());
    }

    private static List<Path> allowedRoots(Path workingDirectory, Set<Path> values) throws IOException {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        if (values != null) {
            for (Path value : values) {
                Path normalized = Objects.requireNonNull(value, "readable root")
                        .toAbsolutePath()
                        .normalize();
                if (Files.exists(normalized)) {
                    result.add(normalized.toRealPath());
                }
            }
        }
        if (result.isEmpty()) {
            result.add(workingDirectory);
        }
        return List.copyOf(result);
    }

    private static Path requireDirectory(Path value) throws IOException {
        Path normalized = Objects.requireNonNull(value, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        Path actual = normalized.toRealPath();
        if (!Files.isDirectory(actual)) {
            throw new IllegalArgumentException("working directory is not a directory: " + value);
        }
        return actual;
    }

    private static CacheKey key(Path directory, Set<Path> readableRoots, AgentsInstructionSettings settings)
            throws IOException {
        List<String> roots = readableRoots == null
                ? List.of()
                : readableRoots.stream()
                        .map(value -> canonicalKeyPath(Objects.requireNonNull(value, "readable root")))
                        .sorted()
                        .toList();
        return new CacheKey(requireDirectory(directory), roots, settings.fingerprint());
    }

    private static String canonicalKeyPath(Path value) {
        Path normalized = value.toAbsolutePath().normalize();
        try {
            return Files.exists(normalized) ? normalized.toRealPath().toString() : normalized.toString();
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot resolve readable root: " + value, failure);
        }
    }

    private record CacheKey(Path workingDirectory, List<String> readableRoots, String settingsFingerprint) {}

    private record CacheEntry(CacheKey key, AgentsInstructionResolution resolution) {}
}
