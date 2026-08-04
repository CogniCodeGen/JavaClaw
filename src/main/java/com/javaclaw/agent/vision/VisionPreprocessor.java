package com.javaclaw.agent.vision;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.chat.ChatMessage;
import com.javaclaw.util.ProjectAccessPolicy;
import com.javaclaw.util.SensitiveDataRedactor;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 视觉预处理器 — 用多模态模型把用户附带的图片一次性转成文本描述，
 * 后续的工具路由 / 目标分解 / 子智能体委派全部回到纯文本管道。
 *
 * <p>设计动机：避免编排器同时处理"视觉理解 + 工具调度"两件事；
 * 也避免子智能体（纯文本工具调用参数）拿不到图片内容而假装没有看到。</p>
 *
 * @author JavaClaw
 */
public class VisionPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(VisionPreprocessor.class);

    private static final String SYS_PROMPT = """
            你是图片分析助手。用简洁中文描述用户附带的每张图片，每张不超过 3 行：
            - 主体内容（人物 / 物体 / 场景 / 界面）
            - 明显的文字（OCR，保留原文）
            - 与用户提问相关的视觉细节
            - 若识别到密码、令牌、验证码、Cookie、私钥或会话值，只写“检测到凭据，内容已隐藏”，不得转录具体值

            输出格式，严格遵守：
            【图片1】...
            【图片2】...（多张图片依次编号）

            不要加开场白或总结。如果无法辨认，就写"无法辨认具体内容"。
            """;

    /** OCR 文字识别提示词：只输出文字本身，尽量保留排版 */
    private static final String OCR_PROMPT = """
            你是 OCR 文字识别引擎。请提取图片中的全部可见文字，要求：
            - 按自然阅读顺序输出（自上而下、从左到右）
            - 保留原文语言、标点、换行与分段，尽量还原排版
            - 表格用 Markdown 表格还原
            - 只输出识别到的文字本身，不要添加任何解释、标题或开场白
            - 密码、令牌、验证码、Cookie、私钥或会话值必须替换为“<已隐藏>”，不得输出具体值
            - 若图中没有可识别文字，仅输出：（未检测到文字）
            """;

    /** 视觉预处理的硬超时，超时则回退为 null（让调用方继续原路径） */
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    /** OCR 单图超时（识别通常比描述更耗时，放宽到 90 秒） */
    private static final Duration OCR_TIMEOUT = Duration.ofSeconds(90);

    private final ChatModelBase model;
    private final GenerateOptions generateOptions;
    /** 视觉分析模型调用的 token 用量上报；null 时跳过统计 */
    private final TokenTracker tokenTracker;

    public VisionPreprocessor(ChatModelBase model) {
        this(model, null);
    }

    public VisionPreprocessor(ChatModelBase model, TokenTracker tokenTracker) {
        this.model = model;
        this.tokenTracker = tokenTracker;
        this.generateOptions = GenerateOptions.builder().build();
    }

    /**
     * 分析 attachments 中的所有图片，返回合并的描述文本。
     *
     * @return 非空描述文本；无图片 / 分析失败 / 超时时返回 null
     */
    public String describe(String userInput, List<File> attachments) {
        List<File> images = collectImages(attachments);
        if (images.isEmpty()) {
            return null;
        }

        List<ContentBlock> blocks = new ArrayList<>();
        for (File img : images) {
            ImageBlock block = buildImageBlock(img);
            if (block != null) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }

        String question = (userInput == null || userInput.isBlank()) ? "（用户未输入文字）" : userInput.trim();
        blocks.add(TextBlock.builder()
                .text("用户提问：" + question + "\n请按规定格式输出每张图片的分析。")
                .build());

        Msg sysMsg = Msg.builder().role(MsgRole.SYSTEM).name("system").textContent(SYS_PROMPT).build();
        Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").content(blocks).build();

        try {
            StringBuilder out = new StringBuilder();
            List<ChatResponse> responses = model.stream(
                    List.of(sysMsg, userMsg), List.of(), generateOptions
            ).collectList().block(TIMEOUT);

            if (responses != null) {
                for (ChatResponse resp : responses) {
                    if (resp.getContent() == null) continue;
                    for (ContentBlock b : resp.getContent()) {
                        if (b instanceof TextBlock tb && tb.getText() != null) {
                            out.append(tb.getText());
                        }
                    }
                }
            }
            if (tokenTracker != null) {
                long[] usage = TokenTracker.extractUsage(responses);
                tokenTracker.recordModelUsage("VisionPreprocessor", usage[0], usage[1]);
            }

            String desc = out.toString().trim();
            if (desc.isEmpty()) {
                log.warn("视觉预处理返回空内容 — {} 张图片", images.size());
                return null;
            }
            if (SensitiveDataRedactor.containsLikelyCredential(desc)) {
                return "图片中检测到疑似凭据，具体内容已隐藏。";
            }
            log.info("视觉预处理完成 — {} 张图片，描述 {} 字符", images.size(), desc.length());
            return desc;
        } catch (Exception e) {
            log.warn("视觉预处理失败（回退为直传图片）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 对单张图片做 OCR 文字识别。
     *
     * <p>复用多模态模型与 {@link #buildImageBlock}，仅替换提示词为 OCR 专用指令。</p>
     *
     * @param image 图片文件
     * @return 识别出的文字；失败 / 超时 / 无内容时返回 null（调用方据此降级）
     */
    public String ocrImage(File image) {
        if (image == null || !ProjectAccessPolicy.isProjectFilePath(image.toPath()) || !image.isFile()) {
            return null;
        }
        ImageBlock block = buildImageBlock(image);
        if (block == null) {
            return null;
        }
        return runOcr(block, image.getName());
    }

    /**
     * 对内存图片做 OCR，不创建临时文件，也不会绕过项目文件边界读取磁盘。
     * PDF 扫描页等已经在项目文件解析过程中得到的图像应使用此入口。
     */
    public String ocrImage(BufferedImage image) {
        if (image == null) return null;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", bytes)) {
                return null;
            }
            ImageBlock block = buildImageBlock(bytes.toByteArray(), "image/png");
            return runOcr(block, "内存图像");
        } catch (IOException e) {
            log.warn("内存图片编码失败");
            return null;
        }
    }

    private String runOcr(ImageBlock block, String sourceLabel) {
        if (block == null || model == null) return null;

        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(block);
        blocks.add(TextBlock.builder().text("请识别这张图片中的全部文字，按要求输出。").build());

        Msg sysMsg = Msg.builder().role(MsgRole.SYSTEM).name("system").textContent(OCR_PROMPT).build();
        Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").content(blocks).build();

        try {
            StringBuilder out = new StringBuilder();
            List<ChatResponse> responses = model.stream(
                    List.of(sysMsg, userMsg), List.of(), generateOptions
            ).collectList().block(OCR_TIMEOUT);

            if (responses != null) {
                for (ChatResponse resp : responses) {
                    if (resp.getContent() == null) continue;
                    for (ContentBlock b : resp.getContent()) {
                        if (b instanceof TextBlock tb && tb.getText() != null) {
                            out.append(tb.getText());
                        }
                    }
                }
            }
            if (tokenTracker != null) {
                long[] usage = TokenTracker.extractUsage(responses);
                tokenTracker.recordModelUsage("OcrRecognize", usage[0], usage[1]);
            }

            String text = out.toString().trim();
            if (text.isEmpty()) {
                log.warn("OCR 返回空内容: {}", sourceLabel);
                return null;
            }
            if (SensitiveDataRedactor.containsLikelyCredential(text)) {
                return "（检测到疑似凭据，OCR 内容已隐藏）";
            }
            log.info("OCR 完成 — {}，识别 {} 字符", sourceLabel, text.length());
            return text;
        } catch (Exception e) {
            log.warn("OCR 识别失败: {}", sourceLabel);
            return null;
        }
    }

    private List<File> collectImages(List<File> attachments) {
        List<File> images = new ArrayList<>();
        if (attachments != null) {
            for (File f : attachments) {
                if (f != null && ProjectAccessPolicy.isProjectFilePath(f.toPath())
                        && ChatMessage.isImageFile(f)) {
                    images.add(f);
                }
            }
        }
        return images;
    }

    private ImageBlock buildImageBlock(File imageFile) {
        try {
            byte[] bytes = Files.readAllBytes(
                    ProjectAccessPolicy.requireProjectFilePath(imageFile.toPath()));
            return buildImageBlock(bytes, mediaType(imageFile));
        } catch (IOException | SecurityException e) {
            log.warn("读取图片失败: {}", imageFile.getName(), e);
            return null;
        }
    }

    private ImageBlock buildImageBlock(byte[] bytes, String mediaType) {
        String b64 = Base64.getEncoder().encodeToString(bytes);
        return ImageBlock.builder()
                .source(Base64Source.builder()
                        .mediaType(mediaType)
                        .data(b64)
                        .build())
                .build();
    }

    private String mediaType(File file) {
        String ext = ChatMessage.getFileExtension(file).toLowerCase();
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
