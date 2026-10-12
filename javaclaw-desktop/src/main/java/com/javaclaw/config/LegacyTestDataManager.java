package com.javaclaw.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 历史 JUnit 临时数据的只读扫描与显式清理入口。
 *
 * <p>扫描本身不修改任何文件；删除只能作用于本次扫描返回、且名称为
 * {@code junit-*} 的直接子目录。正式数据目录和 {@code target/test-data}
 * 永远不在候选范围内。</p>
 */
public final class LegacyTestDataManager {
    private static final String JUNIT_PREFIX = "junit-";
    private static final Path JAVACLAW_MARKER = Path.of("data", "javaclaw.mv.db");

    private LegacyTestDataManager() {}

    public record Candidate(Path root, Path path, long bytes) {
        public Candidate {
            root = root.toAbsolutePath().normalize();
            path = path.toAbsolutePath().normalize();
        }
    }

    /** 扫描系统临时目录与项目根目录；不会自动删除。 */
    public static List<Candidate> scanDefaultLocations() {
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(Path.of(System.getProperty("java.io.tmpdir")));
        roots.add(Path.of(System.getProperty("user.dir")));
        return scan(roots);
    }

    /** 供 UI 和测试使用的只读扫描。 */
    public static List<Candidate> scan(Iterable<Path> roots) {
        List<Candidate> result = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Path rawRoot : roots) {
            if (rawRoot == null) continue;
            Path root = rawRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var children = Files.list(root)) {
                children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().startsWith(JUNIT_PREFIX))
                        .filter(LegacyTestDataManager::hasJavaClawMarker)
                        .map(path -> path.toAbsolutePath().normalize())
                        .filter(seen::add)
                        .forEach(path -> result.add(new Candidate(root, path, directorySize(path))));
            } catch (IOException ignored) {
                // 单个根不可读不应阻断其他位置的扫描。
            }
        }
        result.sort(Comparator.comparing(candidate -> candidate.path().toString()));
        return List.copyOf(result);
    }

    /**
     * 删除用户已经确认的扫描候选。
     *
     * @return 实际删除的候选目录数
     */
    public static int deleteConfirmed(List<Candidate> candidates) throws IOException {
        int deleted = 0;
        for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
            validateCandidate(candidate);
            if (!Files.exists(candidate.path())) continue;
            try (var paths = Files.walk(candidate.path())) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
            deleted++;
        }
        return deleted;
    }

    private static void validateCandidate(Candidate candidate) {
        if (candidate == null
                || candidate.path().getParent() == null
                || !candidate.path().getParent().equals(candidate.root())
                || !candidate.path().getFileName().toString().startsWith(JUNIT_PREFIX)
                || !Files.isDirectory(candidate.root(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(candidate.path(), LinkOption.NOFOLLOW_LINKS)
                || !hasJavaClawMarker(candidate.path())) {
            throw new SecurityException("拒绝清理非扫描范围目录");
        }
    }

    private static boolean hasJavaClawMarker(Path candidate) {
        return Files.isRegularFile(candidate.resolve(JAVACLAW_MARKER),
                LinkOption.NOFOLLOW_LINKS);
    }

    private static long directorySize(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException ignored) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
