package com.javaclaw.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Turn 启动时冻结的工具来源、revision 与权限上限。
 *
 * <p>搜索只能缩小或展开此快照，不能加载新来源。执行层仍必须重新检查 enabled、revision 和实时撤权。
 *
 * @param turnId 所属 Turn
 * @param catalogRevision 目录整体版本
 * @param tools 无重名工具集合
 * @param permissionCeiling 冻结权限上限
 * @param capturedAt 捕获时间
 */
public record ToolCatalogSnapshot(
        TurnId turnId,
        long catalogRevision,
        List<ToolDescriptor> tools,
        PermissionProfile permissionCeiling,
        Instant capturedAt) {
    /** 校验唯一名称并固定排序。 */
    public ToolCatalogSnapshot {
        Objects.requireNonNull(turnId, "turnId");
        catalogRevision = Preconditions.positive(catalogRevision, "catalogRevision");
        Objects.requireNonNull(permissionCeiling, "permissionCeiling");
        Objects.requireNonNull(capturedAt, "capturedAt");
        LinkedHashMap<String, ToolDescriptor> unique = new LinkedHashMap<>();
        for (ToolDescriptor tool : tools) {
            if (unique.putIfAbsent(tool.identity().name(), tool) != null) {
                throw new IllegalArgumentException(
                        "duplicate tool name: " + tool.identity().name());
            }
        }
        tools = unique.values().stream()
                .sorted(Comparator.comparing(value -> value.identity().name()))
                .toList();
    }

    /**
     * 在冻结目录中搜索名称、说明和标签。
     *
     * @param query 非空查询
     * @param limit 最大结果数
     * @return 按稳定名称排序的匹配项
     */
    public List<ToolDescriptor> search(String query, int limit) {
        String normalized = Preconditions.text(query, "query").toLowerCase(Locale.ROOT);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return tools.stream()
                .filter(tool -> matches(tool, normalized))
                .limit(limit)
                .toList();
    }

    /**
     * 读取冻结工具；名称或 revision 不一致时失败。
     *
     * @param identity 模型请求中的工具身份
     * @return 冻结描述
     */
    public ToolDescriptor require(ToolIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return tools.stream()
                .filter(tool -> tool.identity().equals(identity))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("tool is not present in the frozen catalog"));
    }

    /**
     * 计算可持久化到 Turn 的稳定目录摘要。
     *
     * <p>摘要覆盖目录版本、权限版本、工具身份和输入输出 Schema；捕获时间与 Turn ID 不参与计算。
     *
     * @return SHA-256 小写十六进制
     */
    public String digest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, Long.toString(catalogRevision));
            update(digest, permissionCeiling.id());
            update(digest, Long.toString(permissionCeiling.version()));
            for (ToolDescriptor tool : tools) {
                update(digest, tool.identity().producerId());
                update(digest, tool.identity().name());
                update(digest, Long.toString(tool.identity().revision()));
                update(digest, tool.inputSchema().sha256());
                update(digest, tool.outputSchema().sha256());
                update(digest, tool.risk().name());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static boolean matches(ToolDescriptor tool, String query) {
        if (tool.identity().name().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (tool.description().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return tool.tags().stream().anyMatch(tag -> tag.contains(query));
    }
}
