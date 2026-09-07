package com.javaclaw.builtin.contracts;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 独立版本的依赖准备证据；快照并非文件系统事务，观察到的变化不证明由安装器造成。
 *
 * @param operationId 服务端绑定的准备操作
 * @param manager 实际调用的原生包管理器
 * @param before 执行前的有界观察
 * @param after 执行后的有界观察；尚未采集或失败时为空
 * @param observedChanges 两次观察均能确认的差异，不含因截断而推断的删除
 * @param artifacts 原文和原生命令输出的 Workspace CAS 引用，内容均为不可信数据
 * @param complete 所有请求的观察与证据是否完成；排除目录、预算或失败均为 false
 */
public record DependencyEvidence(
        String operationId,
        CodingContracts.PackageManager manager,
        Inventory before,
        Optional<Inventory> after,
        List<Change> observedChanges,
        List<Artifact> artifacts,
        boolean complete) {
    /** 复制集合并约束独立 Schema 的大小。 */
    public DependencyEvidence {
        if (operationId == null || !operationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}")) {
            throw new IllegalArgumentException("invalid dependency evidence identity");
        }
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
        observedChanges = List.copyOf(observedChanges);
        artifacts = List.copyOf(artifacts);
        if (observedChanges.size() > 20_032
                || artifacts.size() > 256
                || complete
                        && (!before.complete()
                                || after.isEmpty()
                                || !after.orElseThrow().complete())) {
            throw new IllegalArgumentException("invalid dependency evidence bounds or completeness");
        }
    }

    /**
     * @param files 已读取完整内容并计算摘要的普通文件，最多 10000 项
     * @param scannedBytes 实际扫描字节数，非负
     * @param complete 是否没有遗漏；不表示扫描期间外部用户未修改文件
     * @param omissions 排除路径、预算和错误说明，最多 256 条、每条 4096 字符
     */
    public record Inventory(List<FileDigest> files, long scannedBytes, boolean complete, List<String> omissions) {
        /** 复制有界快照；遗漏不能同时标记完整。 */
        public Inventory {
            files = List.copyOf(files);
            omissions = List.copyOf(omissions);
            if (files.size() > 10_000
                    || scannedBytes < 0
                    || omissions.size() > 256
                    || omissions.stream().anyMatch(value -> value.length() > 4096)
                    || complete && !omissions.isEmpty()) {
                throw new IllegalArgumentException("invalid dependency inventory");
            }
        }
    }

    /**
     * @param path 冻结根内相对路径
     * @param sizeBytes 完整文件字节数
     * @param sha256 完整原始字节摘要
     */
    public record FileDigest(String path, long sizeBytes, String sha256) {
        /** 校验路径、摘要和大小。 */
        public FileDigest {
            path = CodingContractValidation.path(path);
            if (path.equals(".")) {
                throw new IllegalArgumentException("inventory entry must be a file");
            }
            digest(sha256);
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("negative file size");
            }
        }
    }

    /**
     * @param path 两次观察的相对路径
     * @param beforeSha256 先前摘要；仅完整前快照允许以空表示未发现
     * @param afterSha256 后续摘要；仅完整后快照允许以空表示未发现
     */
    public record Change(String path, Optional<String> beforeSha256, Optional<String> afterSha256) {
        /** 不接受同一摘要或两端均空的虚假变化。 */
        public Change {
            path = CodingContractValidation.path(path);
            if (path.equals(".")) {
                throw new IllegalArgumentException("changed entry must be a file");
            }
            beforeSha256.ifPresent(DependencyEvidence::digest);
            afterSha256.ifPresent(DependencyEvidence::digest);
            if (beforeSha256.equals(afterSha256)) {
                throw new IllegalArgumentException("unchanged dependency file");
            }
        }
    }

    /**
     * @param source 相对来源路径或 stdout、stderr、pip-report 等固定来源名称
     * @param kind manifest、lock、native-output 或 pip-report；不声称已解析依赖图
     * @param phase before、after 或 execution
     * @param sha256 Workspace CAS 内原始字节摘要
     * @param sizeBytes 保留字节数
     * @param complete 是否保存此来源的完整内容
     */
    public record Artifact(String source, String kind, String phase, String sha256, long sizeBytes, boolean complete) {
        /** 校验有界来源与摘要。 */
        public Artifact {
            if (source == null
                    || source.isEmpty()
                    || source.length() > 4096
                    || !List.of("manifest", "lock", "native-output", "pip-report")
                            .contains(kind)
                    || !List.of("before", "after", "execution").contains(phase)
                    || sizeBytes < 0) {
                throw new IllegalArgumentException("invalid dependency artifact");
            }
            digest(sha256);
        }
    }

    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid dependency digest");
        }
    }
}
