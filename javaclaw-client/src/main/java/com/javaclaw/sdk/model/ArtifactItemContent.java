package com.javaclaw.sdk.model;

/**
 * 版本化领域产物（规格、节点输出或分页 OCR）。
 *
 * @param artifactId 领域产物稳定标识
 * @param category 领域分类
 * @param name 展示名
 * @param revision 不可变产物修订
 * @param content 完整有界正文
 * @param sources Item 或附件来源引用
 * @param document 完整原始 JSON，保留未知扩展
 */
public record ArtifactItemContent(
        String artifactId,
        String category,
        String name,
        long revision,
        String content,
        java.util.List<String> sources,
        JsonDocument document)
        implements ItemContent {
    /** 固定集合，防止界面修改事件快照。 */
    public ArtifactItemContent {
        sources = java.util.List.copyOf(sources);
    }

    @Override
    public String kind() {
        return "artifact";
    }
}
