package com.javaclaw.sdk.model;

import java.util.List;
import java.util.Map;

/**
 * OpenSpec 文件导入预览，不是已采用的 SDD 状态。
 *
 * @param documents 受支持的相对 Markdown 路径与原文
 * @param sourceSha256 来源 ZIP 的 SHA-256，便于用户核对所选文件
 * @param warnings 导入限制与状态语义提示
 */
public record OpenSpecDraftInfo(Map<String, String> documents, String sourceSha256, List<String> warnings) {
    /** 固定预览快照；文件后续变化不会自动更新草稿。 */
    public OpenSpecDraftInfo {
        documents = Map.copyOf(documents);
        warnings = List.copyOf(warnings);
    }
}
