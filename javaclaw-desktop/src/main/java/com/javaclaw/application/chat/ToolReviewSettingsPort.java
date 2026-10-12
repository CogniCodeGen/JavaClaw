package com.javaclaw.application.chat;

import com.javaclaw.config.ToolReviewMode;

import java.util.concurrent.CompletionStage;

/**
 * 工具审核策略的应用端口。
 *
 * <p>实现必须线程安全；更新立即作用于当前工作区的内存状态，持久化在后台按提交顺序执行。
 * 返回结果只代表保存完成，不代表授权或业务操作成功。保存失败不回退已经生效的内存策略，
 * 调用方应明确提示未持久化；已接受的写入必须保留提交时的工作区身份。</p>
 */
public interface ToolReviewSettingsPort {
    ToolReviewMode current();
    CompletionStage<Void> update(ToolReviewMode mode);
}
