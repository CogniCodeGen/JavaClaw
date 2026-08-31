package com.javaclaw.agent.prompt;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一次 AGENTS.md 指令链解析结果；正文只在模型边界内使用，协议投影不得返回 {@link Source#content()}。
 *
 * @param workingDirectory 实际 Thread 工作目录
 * @param sources 按全局到项目深层排列的有效来源
 * @param warnings 截断或环境替换等非静默状态
 * @param totalProjectBytes 实际注入的项目层 UTF-8 字节数，不含全局文件
 */
public record AgentsInstructionResolution(
        Path workingDirectory, List<Source> sources, List<String> warnings, long totalProjectBytes) {
    static final String REPLACEMENT_NOTICE = "这些 AGENTS.md 指令替换此前提供的所有 AGENTS.md 指令。";
    static final String REMOVAL_NOTICE = "此前提供的 AGENTS.md 指令不再适用。";
    private static final String PROJECT_SEPARATOR = "\n\n--- project-doc ---\n\n";

    /** 固定路径和有序来源，不允许解析后再改写模型可见链。 */
    public AgentsInstructionResolution {
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (totalProjectBytes < 0) {
            throw new IllegalArgumentException("totalProjectBytes cannot be negative");
        }
    }

    /** 创建不含项目指令的隔离结果；MCP sampling 等调用使用此值。 */
    public static AgentsInstructionResolution empty(Path workingDirectory) {
        return new AgentsInstructionResolution(workingDirectory, List.of(), List.of(), 0);
    }

    /** 返回模型可见的 Codex 风格 USER 上下文；文件路径和普通解析警告只留在审计元数据。 */
    public String asModelContext() {
        StringBuilder body = new StringBuilder();
        modelStateNotice().ifPresent(body::append);
        boolean hasPrevious = !body.isEmpty();
        boolean previousWasGlobal = false;
        for (Source source : sources) {
            if (hasPrevious) {
                body.append("project".equals(source.scope()) && previousWasGlobal ? PROJECT_SEPARATOR : "\n\n");
            }
            body.append(source.content());
            hasPrevious = true;
            previousWasGlobal = "global".equals(source.scope());
        }
        return new StringBuilder()
                .append("# AGENTS.md instructions for ")
                .append(workingDirectory)
                .append("\n\n<INSTRUCTIONS>\n")
                .append(body)
                .append("\n</INSTRUCTIONS>")
                .toString();
    }

    /** 返回来源正文和元数据的有序摘要，用于判断缓存刷新是否替换了有效链。 */
    public String fingerprint() {
        return PromptHashes.sequence(sources.stream()
                .flatMap(value -> java.util.stream.Stream.of(
                        value.scope(),
                        value.path().toString(),
                        value.sha256(),
                        Long.toString(value.bytes()),
                        Boolean.toString(value.truncated())))
                .toList());
    }

    /** 是否存在需要注入的来源或替换状态。 */
    public boolean hasModelContext() {
        return !sources.isEmpty() || modelStateNotice().isPresent();
    }

    private java.util.Optional<String> modelStateNotice() {
        return warnings.stream()
                .filter(value -> value.equals(REPLACEMENT_NOTICE) || value.equals(REMOVAL_NOTICE))
                .findFirst();
    }

    /**
     * 一个实际采用的全局或项目指令文件。
     *
     * @param scope {@code global} 或 {@code project}
     * @param path 解析符号链接后的绝对路径
     * @param bytes 实际注入字节数
     * @param sha256 实际注入正文的 SHA-256
     * @param truncated 是否因项目总预算截断
     * @param content 模型可见正文；不得进入普通日志、诊断或 Wire DTO
     */
    public record Source(String scope, Path path, long bytes, String sha256, boolean truncated, String content) {
        /** 校验元数据与正文，保留原始空白及文件顺序。 */
        public Source {
            if (!java.util.Set.of("global", "project").contains(scope)) {
                throw new IllegalArgumentException("unknown AGENTS.md source scope");
            }
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            sha256 = Objects.requireNonNull(sha256, "sha256");
            content = Objects.requireNonNull(content, "content");
            if (bytes < 0 || !sha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("invalid AGENTS.md source metadata");
            }
        }
    }
}
