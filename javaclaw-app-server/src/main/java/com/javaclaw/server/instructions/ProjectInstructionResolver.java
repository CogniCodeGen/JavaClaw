package com.javaclaw.server.instructions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.InstructionScope;
import com.javaclaw.api.InstructionSourceResolution;

/** 从受管全局根和 Workspace 目录层级解析下一 Turn 的项目约定。 */
public final class ProjectInstructionResolver {
    /** 每个来源层级允许进入 Prompt 的最大 UTF-8 字节数。 */
    public static final int SCOPE_BYTE_LIMIT = 32 * 1024;

    private static final String OVERRIDE_NAME = "AGENTS.override.md";
    private static final String DEFAULT_NAME = "AGENTS.md";

    private final Path globalStateRoot;
    private final Clock clock;
    private final InstructionFileReader reader = new InstructionFileReader();

    /**
     * 创建解析器。
     *
     * @param globalStateRoot 本地安装的受管状态根
     * @param clock 平台时钟
     */
    public ProjectInstructionResolver(Path globalStateRoot, Clock clock) {
        this.globalStateRoot = Objects.requireNonNull(globalStateRoot, "globalStateRoot")
                .toAbsolutePath()
                .normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 按全局、Workspace 根到 execution root 的顺序解析有效约定。
     *
     * <p>每个目录只选择 override、默认文件或安全 fallback 中的第一个。符号链接不会被读取，正文只存在于返回对象的内部字段。
     *
     * @param workspaceRoot Workspace 根目录
     * @param executionRoot 当前执行根；必须位于 Workspace 根内
     * @param fallbackBasename Workspace 配置的安全备用文件名
     * @return 脱敏清单和内部 Prompt 正文
     */
    public ResolvedInstructions resolve(Path workspaceRoot, Path executionRoot, Optional<String> fallbackBasename) {
        Path normalizedWorkspace = normalized(workspaceRoot, "workspaceRoot");
        Path normalizedExecution = normalized(executionRoot, "executionRoot");
        if (!normalizedExecution.startsWith(normalizedWorkspace)) {
            throw new IllegalArgumentException("executionRoot must be inside workspaceRoot");
        }
        Optional<Path> workspace = availableRealDirectory(normalizedWorkspace);
        if (workspace.isEmpty()) {
            return resolved(readGlobal(), List.of(), List.of("WORKSPACE_ROOT_UNAVAILABLE"));
        }
        Optional<Path> execution = availableRealDirectory(normalizedExecution);
        if (execution.isEmpty()) {
            return resolved(readGlobal(), List.of(), List.of("EXECUTION_ROOT_UNAVAILABLE"));
        }
        if (!execution.orElseThrow().startsWith(workspace.orElseThrow())) {
            throw new IllegalArgumentException("executionRoot must be inside workspaceRoot");
        }
        Optional<String> fallback = checkedFallback(fallbackBasename);
        List<InstructionFileReader.ReadResult> global = readGlobal();
        List<InstructionFileReader.ReadResult> project =
                readProject(workspace.orElseThrow(), execution.orElseThrow(), fallback);
        return resolved(global, project, List.of());
    }

    private List<InstructionFileReader.ReadResult> readGlobal() {
        Optional<Path> selected = select(globalStateRoot, Optional.empty());
        if (selected.isEmpty()) {
            return List.of();
        }
        Path path = selected.orElseThrow();
        return List.of(
                reader.read(InstructionScope.GLOBAL, path, path.getFileName().toString(), SCOPE_BYTE_LIMIT));
    }

    private List<InstructionFileReader.ReadResult> readProject(
            Path workspace, Path execution, Optional<String> fallback) {
        List<InstructionFileReader.ReadResult> results = new ArrayList<>();
        int remaining = SCOPE_BYTE_LIMIT;
        for (Path directory : hierarchy(workspace, execution)) {
            Optional<Path> selected = select(directory, fallback);
            if (selected.isEmpty()) {
                continue;
            }
            Path file = selected.orElseThrow();
            String relative = portable(workspace.relativize(file));
            InstructionFileReader.ReadResult result = reader.read(InstructionScope.PROJECT, file, relative, remaining);
            results.add(result);
            remaining = Math.max(0, remaining - Math.toIntExact(result.source().includedBytes()));
        }
        return List.copyOf(results);
    }

    private ResolvedInstructions resolved(
            List<InstructionFileReader.ReadResult> global,
            List<InstructionFileReader.ReadResult> project,
            List<String> externalWarnings) {
        List<InstructionFileReader.ReadResult> all = new ArrayList<>(global.size() + project.size());
        all.addAll(global);
        all.addAll(project);
        List<InstructionSourceResolution> sources =
                all.stream().map(InstructionFileReader.ReadResult::source).toList();
        List<String> warnings = new ArrayList<>(warnings(sources));
        warnings.addAll(externalWarnings);
        warnings = warnings.stream().distinct().toList();
        String prompt = prompt(all);
        InstructionResolution resolution = new InstructionResolution(
                sources,
                digest(sources, warnings),
                included(sources, InstructionScope.GLOBAL),
                included(sources, InstructionScope.PROJECT),
                warnings,
                clock.instant());
        return new ResolvedInstructions(resolution, prompt);
    }

    private static Optional<Path> select(Path directory, Optional<String> fallback) {
        List<String> names = new ArrayList<>(List.of(OVERRIDE_NAME, DEFAULT_NAME));
        fallback.filter(name -> !names.contains(name)).ifPresent(names::add);
        for (String name : names) {
            Path candidate = directory.resolve(name);
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static List<Path> hierarchy(Path workspace, Path execution) {
        List<Path> directories = new ArrayList<>();
        Path current = workspace;
        directories.add(current);
        if (workspace.equals(execution)) {
            return List.copyOf(directories);
        }
        for (Path segment : workspace.relativize(execution)) {
            if (segment.toString().isEmpty()) {
                continue;
            }
            current = current.resolve(segment);
            directories.add(current);
        }
        return directories;
    }

    private static Optional<String> checkedFallback(Optional<String> value) {
        return Objects.requireNonNull(value, "fallbackBasename").map(name -> {
            String checked = name.strip();
            if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
                throw new IllegalArgumentException("fallback basename is unsafe");
            }
            return checked;
        });
    }

    private static Path normalized(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }

    private static Optional<Path> availableRealDirectory(Path normalized) {
        try {
            if (Files.isSymbolicLink(normalized)) {
                return Optional.empty();
            }
            Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(real);
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    private static String prompt(List<InstructionFileReader.ReadResult> results) {
        StringBuilder content = new StringBuilder();
        for (InstructionFileReader.ReadResult result : results) {
            if (result.source().errorCode().isPresent()) {
                continue;
            }
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append("### 项目约定 [")
                    .append(result.source().scope())
                    .append(": ")
                    .append(result.source().relativePath())
                    .append("]\n")
                    .append(result.content());
        }
        return content.toString();
    }

    private static List<String> warnings(List<InstructionSourceResolution> sources) {
        List<String> warnings = new ArrayList<>();
        for (InstructionSourceResolution source : sources) {
            source.errorCode().ifPresent(warnings::add);
            if (source.truncated()) {
                warnings.add(source.scope() + "_TRUNCATED");
            }
        }
        return warnings.stream().distinct().toList();
    }

    private static long included(List<InstructionSourceResolution> sources, InstructionScope scope) {
        return sources.stream()
                .filter(source -> source.scope() == scope)
                .mapToLong(InstructionSourceResolution::includedBytes)
                .sum();
    }

    private static String digest(List<InstructionSourceResolution> sources, List<String> warnings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (InstructionSourceResolution source : sources) {
                update(digest, source.scope().name());
                update(digest, source.relativePath());
                String identity = source.digest()
                        .map(value -> "DIGEST:" + value)
                        .orElseGet(() -> "ERROR:" + source.errorCode().orElseThrow());
                update(digest, identity);
                update(digest, Long.toString(source.byteCount()));
                update(digest, Long.toString(source.includedBytes()));
                update(digest, Boolean.toString(source.truncated()));
            }
            warnings.forEach(warning -> update(digest, "WARNING:" + warning));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String portable(Path relative) {
        return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
    }
}
