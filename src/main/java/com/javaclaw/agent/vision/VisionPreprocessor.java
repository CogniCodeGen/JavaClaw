package com.javaclaw.agent.vision;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.chat.ChatMessage;
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

            输出格式，严格遵守：
            【图片1】...
            【图片2】...（多张图片依次编号）

            不要加开场白或总结。如果无法辨认，就写"无法辨认具体内容"。
            """;

    /** 视觉预处理的硬超时，超时则回退为 null（让调用方继续原路径） */
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

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
            log.info("视觉预处理完成 — {} 张图片，描述 {} 字符", images.size(), desc.length());
            return desc;
        } catch (Exception e) {
            log.warn("视觉预处理失败（回退为直传图片）: {}", e.getMessage());
            return null;
        }
    }

    private List<File> collectImages(List<File> attachments) {
        List<File> images = new ArrayList<>();
        if (attachments != null) {
            for (File f : attachments) {
                if (ChatMessage.isImageFile(f)) {
                    images.add(f);
                }
            }
        }
        return images;
    }

    private ImageBlock buildImageBlock(File imageFile) {
        try {
            byte[] bytes = Files.readAllBytes(imageFile.toPath());
            String b64 = Base64.getEncoder().encodeToString(bytes);
            return ImageBlock.builder()
                    .source(Base64Source.builder()
                            .mediaType(mediaType(imageFile))
                            .data(b64)
                            .build())
                    .build();
        } catch (IOException e) {
            log.warn("读取图片失败: {}", imageFile.getName(), e);
            return null;
        }
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
