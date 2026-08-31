package com.javaclaw.sdk.model;

/**
 * 已完成文件修改的只读投影。
 *
 * @param path 相对路径
 * @param change CREATE、UPDATE 或 DELETE
 * @param diff 差异或明确标记的差异摘录
 * @param document 完整原始 JSON，保留未知扩展
 */
public record FileChangeItemContent(String path, String change, String diff, JsonDocument document)
        implements ItemContent {
    @Override
    public String kind() {
        return "fileChange";
    }
}
