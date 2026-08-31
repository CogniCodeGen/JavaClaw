package com.javaclaw.protocol;

import java.util.List;

/**
 * 有界补丁导出结果，不自动应用到父工作区。
 *
 * @param status READY 或 CLEAN
 * @param patchAttachmentSha256 补丁附件哈希；没有变更时为空
 * @param conflicts 结构化冲突的相对文件名
 * @param message 不含凭据的状态说明
 */
public record WireWorktreePatch(String status, String patchAttachmentSha256, List<String> conflicts, String message) {}
