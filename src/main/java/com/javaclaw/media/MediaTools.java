package com.javaclaw.media;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.agent.vision.VisionPreprocessor;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.chat.ChatMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

/**
 * 媒体工具集 — 把图片查看与 OCR 文字识别能力暴露给模型调用。
 *
 * <ul>
 *   <li>{@code view_image} — 在桌面弹窗中打开图片供用户查看（支持鼠标/触摸板缩放、拖拽）。
 *       通过 {@link UserInteractionPort#previewImage} 经端口触发，工具层不直接依赖 JavaFX。</li>
 *   <li>{@code ocr_recognize} — 对图片或 PDF 做 OCR 文字识别：PDF 优先抽取内嵌文本，
 *       扫描件（无内嵌文本）回退为逐页渲染图片再走多模态模型识别。</li>
 * </ul>
 *
 * @author JavaClaw
 */
public class MediaTools {

    private static final Logger log = LoggerFactory.getLogger(MediaTools.class);

    /** PDF 渲染为图片 OCR 时的 DPI（清晰度与速度的折中） */
    private static final int PDF_RENDER_DPI = 150;

    /** OCR 单次最多处理的 PDF 页数，防止超大文档拖垮模型调用 */
    private static final int MAX_PDF_PAGES = 20;

    /** 判定 PDF 为"扫描件"（几乎无内嵌文本）的每页平均字符阈值 */
    private static final int SCANNED_PDF_CHARS_PER_PAGE = 8;

    /** OCR 结果总字符上限，超出截断 */
    private static final int MAX_OCR_CHARS = 20000;

    private final VisionPreprocessor vision;

    public MediaTools(VisionPreprocessor vision) {
        this.vision = vision;
    }

    // ==================== 图片查看 ====================

    @Tool(name = "view_image", description = "在桌面弹窗中打开一张图片供用户查看，支持鼠标滚轮/触摸板缩放与拖拽平移。"
            + "当你生成、下载或定位到一张图片，需要直观展示给用户时使用。支持 png/jpg/jpeg/gif/bmp/webp。")
    public String viewImage(
            @ToolParam(name = "path", description = "图片文件的绝对路径") String path) {
        log.debug("工具调用: view_image('{}')", path);
        try {
            if (path == null || path.isBlank()) {
                return ToolResponse.error("view_image", "路径不能为空");
            }
            File file = new File(path);
            if (!file.isFile()) {
                return ToolResponse.error("view_image", "文件不存在: " + path);
            }
            if (!ChatMessage.isImageFile(file)) {
                return ToolResponse.error("view_image",
                        "不是受支持的图片格式（png/jpg/jpeg/gif/bmp/webp）: " + file.getName());
            }
            UserInteractionPort port = ToolConfirmationManager.getPort();
            if (port == null || !port.isAvailable()) {
                return ToolResponse.error("view_image", "UI 尚未就绪，无法打开图片查看窗口");
            }
            port.previewImage(file.toPath());
            return ToolResponse.success("view_image", "已为用户打开图片查看窗口: " + file.getName());
        } catch (Exception e) {
            log.error("view_image 执行异常", e);
            return ToolResponse.fromException("view_image", e);
        }
    }

    // ==================== OCR 文字识别 ====================

    @Tool(name = "ocr_recognize", description = "对图片或 PDF 文件进行 OCR 文字识别，提取其中的文字内容并返回。"
            + "支持 png/jpg/jpeg/gif/bmp/webp 图片与 PDF（含扫描件）。需要读取图片/PDF 中的文字时使用。")
    public String ocrRecognize(
            @ToolParam(name = "path", description = "图片或 PDF 文件的绝对路径") String path) {
        log.debug("工具调用: ocr_recognize('{}')", path);
        try {
            if (path == null || path.isBlank()) {
                return ToolResponse.error("ocr_recognize", "路径不能为空");
            }
            File file = new File(path);
            if (!file.isFile()) {
                return ToolResponse.error("ocr_recognize", "文件不存在: " + path);
            }
            if (vision == null) {
                return ToolResponse.error("ocr_recognize", "视觉模型未就绪，无法进行 OCR");
            }

            if (ChatMessage.isImageFile(file)) {
                String text = vision.ocrImage(file);
                if (text == null) {
                    return ToolResponse.error("ocr_recognize", "图片 OCR 失败或未识别到文字: " + file.getName());
                }
                return ToolResponse.success("ocr_recognize",
                        "图片《" + file.getName() + "》识别结果：\n" + truncate(text));
            }

            if (ChatMessage.isPdfFile(file)) {
                return ocrPdf(file);
            }

            return ToolResponse.error("ocr_recognize",
                    "不支持的文件类型（仅支持图片与 PDF）: " + file.getName());
        } catch (Exception e) {
            log.error("ocr_recognize 执行异常", e);
            return ToolResponse.fromException("ocr_recognize", e);
        }
    }

    /** PDF OCR：先抽内嵌文本，扫描件则逐页渲染图片再识别 */
    private String ocrPdf(File file) {
        try (PDDocument document = Loader.loadPDF(file)) {
            int pageCount = document.getNumberOfPages();

            // 1. 优先抽取内嵌文本（电子版 PDF 直接命中，无需调用模型）
            String embedded = "";
            try {
                embedded = new PDFTextStripper().getText(document).trim();
            } catch (Exception e) {
                log.warn("PDF 内嵌文本抽取失败，转扫描件识别: {}", e.getMessage());
            }

            boolean scanned = embedded.length() < (long) SCANNED_PDF_CHARS_PER_PAGE * Math.max(1, pageCount);
            if (!embedded.isEmpty() && !scanned) {
                return ToolResponse.success("ocr_recognize",
                        "PDF《" + file.getName() + "》共 " + pageCount + " 页，内嵌文本：\n" + truncate(embedded));
            }

            // 2. 扫描件：逐页渲染为图片再 OCR
            int limit = Math.min(pageCount, MAX_PDF_PAGES);
            PDFRenderer renderer = new PDFRenderer(document);
            StringBuilder sb = new StringBuilder();
            int recognized = 0;
            for (int i = 0; i < limit; i++) {
                File pageImg = null;
                try {
                    BufferedImage image = renderer.renderImageWithDPI(i, PDF_RENDER_DPI);
                    pageImg = File.createTempFile("javaclaw-ocr-", ".png");
                    ImageIO.write(image, "png", pageImg);
                    String pageText = vision.ocrImage(pageImg);
                    sb.append("【第 ").append(i + 1).append(" 页】\n")
                            .append(pageText == null ? "（识别失败）" : pageText).append("\n\n");
                    if (pageText != null) recognized++;
                    if (sb.length() > MAX_OCR_CHARS) break;
                } catch (Exception e) {
                    log.warn("PDF 第 {} 页渲染/识别失败: {}", i + 1, e.getMessage());
                    sb.append("【第 ").append(i + 1).append(" 页】（处理失败）\n\n");
                } finally {
                    deleteQuietly(pageImg);
                }
            }

            if (recognized == 0) {
                return ToolResponse.error("ocr_recognize",
                        "PDF《" + file.getName() + "》未能识别出任何文字（可能为空白或加密文档）");
            }
            String note = pageCount > limit ? "（仅识别前 " + limit + " / " + pageCount + " 页）" : "";
            return ToolResponse.success("ocr_recognize",
                    "PDF《" + file.getName() + "》扫描件识别结果" + note + "：\n" + truncate(sb.toString().trim()));
        } catch (Exception e) {
            log.error("PDF OCR 失败: {}", file.getName(), e);
            return ToolResponse.fromException("ocr_recognize", e);
        }
    }

    private String truncate(String text) {
        if (text.length() <= MAX_OCR_CHARS) return text;
        return text.substring(0, MAX_OCR_CHARS) + "\n...（内容已截断，共 " + text.length() + " 字符）";
    }

    private void deleteQuietly(File f) {
        if (f == null) return;
        try {
            Files.deleteIfExists(f.toPath());
        } catch (Exception ignore) {
            // 临时文件清理失败不影响主流程
        }
    }
}
