package com.javaclaw.desktop.web;

import java.net.URI;
import java.util.Objects;

/** CommonMark 目标的统一 URI 分类；原始目标仍用于服务端来源匹配，编码不授予文件访问权限。 */
public final class MarkdownLinkTarget {
    private MarkdownLinkTarget() {}

    /**
     * 编码 CommonMark 允许的目标空格，保留已有百分号编码、协议与片段语义。
     *
     * @param destination CommonMark 解析器返回的原始目标，不可空
     * @return 用于分类或外部浏览器导航的 URI，不可用来替换文档资源 RPC 的原始 href
     * @throws IllegalArgumentException 目标含其他非法 URI 字符或无效转义
     */
    public static URI parse(String destination) {
        return URI.create(Objects.requireNonNull(destination, "destination").replace(" ", "%20"));
    }

    /**
     * 判断目标是否可作为相对资源候选；实际路径与权限仍由服务端重新核验。
     *
     * @param destination CommonMark 原始目标，不可空
     * @return 目标不含协议或 authority、不是空白或页内锚点时为 true；无效 URI 返回 false
     */
    public static boolean relative(String destination) {
        try {
            URI uri = parse(destination);
            return !uri.isAbsolute()
                    && uri.getRawAuthority() == null
                    && !destination.startsWith("#")
                    && !destination.isBlank();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
