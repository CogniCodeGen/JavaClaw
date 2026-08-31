package com.javaclaw.agent.tool;

import java.net.URI;

/** First-party browser port; implementations fetch only through the Network Broker. */
public interface BrowserGateway {
    /**
     * 通过独立 Browser Service 和 NetworkBroker 获取页面快照；文本长度受 maximumTextCharacters 限制。
     *
     * @throws Exception 目标不在策略内、页面获取失败或资源预算耗尽
     */
    Snapshot snapshot(URI uri, boolean screenshot, int maximumTextCharacters) throws Exception;

    /**
     * 浏览器返回的有界页面结果；截图以附件摘要引用，不向客户端暴露本地路径。
     *
     * @param finalUri 最终访问 URI，非空
     * @param statusCode HTTP 状态码，范围 100 到 599
     * @param title 页面标题；null 归一为空字符串
     * @param text 有界页面文本；null 归一为空字符串
     * @param screenshotAttachmentSha256 截图附件摘要；未截图时可为 null
     */
    record Snapshot(URI finalUri, int statusCode, String title, String text, String screenshotAttachmentSha256) {
        /** 校验最终 URI 和状态码，并归一可选页面文字；不在构造器内访问网络。 */
        public Snapshot {
            if (finalUri == null) {
                throw new NullPointerException("finalUri");
            }
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("invalid HTTP status");
            }
            title = title == null ? "" : title;
            text = text == null ? "" : text;
        }
    }
}
