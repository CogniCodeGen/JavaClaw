package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;

/**
 * PermissionProfile 表单的不可变草稿。
 *
 * @param id 稳定配置标识
 * @param readRoots 每行一个绝对读取根目录
 * @param writeRoots 每行一个绝对写入根目录
 * @param allowDelete 是否允许删除
 * @param followSymbolicLinks 是否允许跟随符号链接
 * @param networkHosts 每行一个主机名
 * @param networkPorts 逗号或换行分隔端口
 * @param tlsOnly 是否只允许 TLS
 * @param executables 每行一个可执行文件规范名
 * @param allowPty 是否允许 PTY
 * @param processSeconds 单进程最长秒数
 * @param allowedTools 每行一个完整工具名
 * @param maximumRisk 最大风险
 * @param approvalRequirement 审批强度
 * @param memoryMiB 最大内存 MiB
 * @param outputMiB 最大输出 MiB
 * @param childProcesses 最大子进程数
 * @param openFiles 最大打开文件数
 */
public record PermissionProfileDraft(
        String id,
        String readRoots,
        String writeRoots,
        boolean allowDelete,
        boolean followSymbolicLinks,
        String networkHosts,
        String networkPorts,
        boolean tlsOnly,
        String executables,
        boolean allowPty,
        long processSeconds,
        String allowedTools,
        ToolRisk maximumRisk,
        ApprovalRequirement approvalRequirement,
        long memoryMiB,
        long outputMiB,
        int childProcesses,
        int openFiles) {
    private static final long MIB = 1024L * 1024L;

    /** 规范化可空文本并保留尚未通过领域校验的数值。 */
    public PermissionProfileDraft {
        id = Objects.requireNonNullElse(id, "");
        readRoots = Objects.requireNonNullElse(readRoots, "");
        writeRoots = Objects.requireNonNullElse(writeRoots, "");
        networkHosts = Objects.requireNonNullElse(networkHosts, "");
        networkPorts = Objects.requireNonNullElse(networkPorts, "");
        executables = Objects.requireNonNullElse(executables, "");
        allowedTools = Objects.requireNonNullElse(allowedTools, "");
        maximumRisk = Objects.requireNonNull(maximumRisk, "maximumRisk");
        approvalRequirement = Objects.requireNonNull(approvalRequirement, "approvalRequirement");
    }

    /**
     * 从权威 PermissionProfile 生成草稿。
     *
     * @param profile 权限快照
     * @return 等价草稿
     */
    public static PermissionProfileDraft from(PermissionProfile profile) {
        PermissionProfile checked = Objects.requireNonNull(profile, "profile");
        return new PermissionProfileDraft(
                checked.id(),
                joinPaths(checked.files().readRoots()),
                joinPaths(checked.files().writeRoots()),
                checked.files().allowDelete(),
                checked.files().followSymbolicLinks(),
                checked.network().hosts().stream().sorted().collect(java.util.stream.Collectors.joining("\n")),
                checked.network().ports().stream()
                        .sorted()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", ")),
                checked.network().tlsOnly(),
                checked.processes().executables().stream().sorted().collect(java.util.stream.Collectors.joining("\n")),
                checked.processes().allowPty(),
                checked.processes().maxRunTime().toSeconds(),
                checked.tools().allowedTools().stream().sorted().collect(java.util.stream.Collectors.joining("\n")),
                checked.tools().maximumRisk(),
                checked.tools().approvalRequirement(),
                checked.resources().memoryBytes() / MIB,
                checked.resources().outputBytes() / MIB,
                checked.resources().childProcesses(),
                checked.resources().openFiles());
    }

    /**
     * 复制模板内容并清空 ID，用于 clone。
     *
     * @return 新配置草稿
     */
    public PermissionProfileDraft cloneDraft() {
        return new PermissionProfileDraft(
                "",
                readRoots,
                writeRoots,
                allowDelete,
                followSymbolicLinks,
                networkHosts,
                networkPorts,
                tlsOnly,
                executables,
                allowPty,
                processSeconds,
                allowedTools,
                maximumRisk,
                approvalRequirement,
                memoryMiB,
                outputMiB,
                childProcesses,
                openFiles);
    }

    /**
     * 转换为完整 PermissionProfile 新版本。
     *
     * @param version 要写入的版本
     * @return 已校验配置
     */
    public PermissionProfile toProfile(long version) {
        if (id.strip().equals("standard")) {
            throw new IllegalArgumentException("内置 standard 只读，请先 clone 为新 ID");
        }
        return new PermissionProfile(
                id,
                version,
                new FilePermission(paths(readRoots), paths(writeRoots), allowDelete, followSymbolicLinks),
                new NetworkPermission(strings(networkHosts), integers(networkPorts), tlsOnly),
                new ProcessPermission(strings(executables), allowPty, Duration.ofSeconds(processSeconds)),
                new ToolPermission(strings(allowedTools), maximumRisk, approvalRequirement),
                new ResourceLimits(
                        Math.multiplyExact(memoryMiB, MIB),
                        Math.multiplyExact(outputMiB, MIB),
                        childProcesses,
                        openFiles));
    }

    /**
     * 返回本地草稿发生变化的权限分区。
     *
     * @param baseline 比较基线
     * @return 简短分区名
     */
    public List<String> changedSections(PermissionProfileDraft baseline) {
        PermissionProfileDraft before = Objects.requireNonNull(baseline, "baseline");
        java.util.ArrayList<String> changed = new java.util.ArrayList<>();
        if (!id.equals(before.id)) {
            changed.add("身份");
        }
        if (fileChanged(before)) {
            changed.add("文件");
        }
        if (networkChanged(before)) {
            changed.add("网络");
        }
        if (processChanged(before)) {
            changed.add("进程/PTY");
        }
        if (toolChanged(before)) {
            changed.add("工具/审批");
        }
        if (resourceChanged(before)) {
            changed.add("资源");
        }
        return List.copyOf(changed);
    }

    private boolean fileChanged(PermissionProfileDraft before) {
        return !readRoots.equals(before.readRoots)
                || !writeRoots.equals(before.writeRoots)
                || allowDelete != before.allowDelete
                || followSymbolicLinks != before.followSymbolicLinks;
    }

    private boolean networkChanged(PermissionProfileDraft before) {
        return !networkHosts.equals(before.networkHosts)
                || !networkPorts.equals(before.networkPorts)
                || tlsOnly != before.tlsOnly;
    }

    private boolean processChanged(PermissionProfileDraft before) {
        return !executables.equals(before.executables)
                || allowPty != before.allowPty
                || processSeconds != before.processSeconds;
    }

    private boolean toolChanged(PermissionProfileDraft before) {
        return !allowedTools.equals(before.allowedTools)
                || maximumRisk != before.maximumRisk
                || approvalRequirement != before.approvalRequirement;
    }

    private boolean resourceChanged(PermissionProfileDraft before) {
        return memoryMiB != before.memoryMiB
                || outputMiB != before.outputMiB
                || childProcesses != before.childProcesses
                || openFiles != before.openFiles;
    }

    private static List<Path> paths(String value) {
        return values(value).stream().map(Path::of).toList();
    }

    private static Set<String> strings(String value) {
        return Set.copyOf(values(value));
    }

    private static Set<Integer> integers(String value) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (String item : values(value)) {
            result.add(Integer.valueOf(item));
        }
        return Set.copyOf(result);
    }

    private static List<String> values(String value) {
        return Arrays.stream(value.split("[,\\n]"))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .distinct()
                .toList();
    }

    private static String joinPaths(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(java.util.stream.Collectors.joining("\n"));
    }
}
