package com.javaclaw.sdk.model;

import java.util.List;

/**
 * 补丁导出结果；导出不代表已应用。
 *
 * @param status READY 或 CLEAN
 * @param patchAttachmentSha256 补丁附件哈希；没有变更时为空
 * @param conflicts 结构化冲突的相对文件名
 * @param message 不含凭据的状态说明
 */
public record WorktreePatchInfo(String status, String patchAttachmentSha256, List<String> conflicts, String message) {
    /** 固定冲突列表快照，避免外部修改 SDK 已接收的结果。 */
    public WorktreePatchInfo {
        conflicts = List.copyOf(conflicts);
    }
}
