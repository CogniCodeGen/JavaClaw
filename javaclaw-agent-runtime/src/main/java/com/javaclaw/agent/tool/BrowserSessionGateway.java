package com.javaclaw.agent.tool;

import java.net.URI;
import java.util.List;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.core.api.AttachmentMetadata;

/** 受治理浏览器交互端口；每个会话绑定一个 Turn，网络、凭据、附件和页面引用在服务端复核。 */
public interface BrowserSessionGateway {
    /** 查询当前工作区已启用站点的非敏感标识与来源，不自动打开浏览器。 */
    List<SiteOption> sites(ToolExecutionContext call);

    /** 创建无原始网络的隔离会话；siteId 为空时只允许当前 URL 的公开源，不恢复任何凭据。 */
    Result open(ToolExecutionContext call, String siteId, URI url) throws Exception;

    /** 执行明确列举的页面操作；不允许任意脚本、宿主路径、Cookie 导出或跨 Turn 会话。 */
    Result act(ToolExecutionContext call, String sessionId, Action action) throws Exception;

    /** 回收指定 Turn 的全部 Browser Service 进程与浏览器子进程。 */
    void closeTurn(String turnId);

    /**
     * 已配置站点的非敏感摘要。
     *
     * @param id 站点标识
     * @param name 展示名称
     * @param origin 明确来源
     * @param revision 当前权限版本
     */
    record SiteOption(String id, String name, URI origin, long revision) {}

    /**
     * 一次有限浏览器操作，无效字段或过期页面引用必须拒绝。
     *
     * @param operation
     *     snapshot/navigate/newTab/closeTab/click/fill/select/press/upload/wait/screenshot/pdf/download/close
     * @param tabId 页面标识；空值使用当前页面
     * @param reference 当前快照中的元素引用
     * @param value 非敏感填充值、URL、键名、下载标识或等待毫秒数
     * @param secretName 站点凭据槽位；只供安全填充，模型不能获取值
     * @param attachmentSha256 已属于当前 Thread 的上传附件摘要
     */
    record Action(
            String operation,
            String tabId,
            String reference,
            String value,
            String secretName,
            String attachmentSha256) {}

    /**
     * 操作结果；artifact 与 displayName 仅在产生附件时有值。
     *
     * @param sessionId 隔离会话标识
     * @param text 已脱敏且有界的快照、页面元素与标签页说明
     * @param artifact 已生成附件元数据，可为空
     * @param displayName 附件展示名，可为空
     */
    record Result(String sessionId, String text, AttachmentMetadata artifact, String displayName) {}
}
