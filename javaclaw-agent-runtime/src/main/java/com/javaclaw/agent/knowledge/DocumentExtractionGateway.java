package com.javaclaw.agent.knowledge;

import java.util.List;

/** 复杂文档解析的进程边界；生产实现必须使用受监督 Worker，不在 App Server JVM 中解析不可信二进制。 */
@FunctionalInterface
public interface DocumentExtractionGateway {
    /** 从有界附件字节提取内容；失败不能退回主 JVM 解析或伪造正文。 */
    String extract(byte[] content, String mediaType, String displayName) throws Exception;

    /** 渲染 PDF 的显式页段，最多二十页；页码从 1 开始，资源不足或不支持时失败关闭。 */
    default List<PageImage> render(byte[] content, int firstPage, int pageCount) throws Exception {
        throw new UnsupportedOperationException("isolated PDF page rendering is unavailable");
    }

    /**
     * Worker 渲染结果，保留真实页号。
     *
     * @param page 一基页号
     * @param png PNG 字节，构造和读取均复制
     */
    record PageImage(int page, byte[] png) {
        /** 限制每页结果大小；不能通过恶意文档生成无界图片。 */
        public PageImage {
            if (page < 1 || png == null || png.length > 5 * 1024 * 1024) {
                throw new IllegalArgumentException("invalid rendered document page");
            }
            png = png.clone();
        }

        @Override
        public byte[] png() {
            return png.clone();
        }
    }
}
