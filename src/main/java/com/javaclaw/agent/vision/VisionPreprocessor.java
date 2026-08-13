package com.javaclaw.agent.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.chat.ChatMessage;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.util.ProjectAccessPolicy;
import com.javaclaw.util.SensitiveDataRedactor;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Multimodal helper whose every model call is owned and budgeted by a framework Run. */
public class VisionPreprocessor {
    private static final Logger log = LoggerFactory.getLogger(VisionPreprocessor.class);
    private static final Duration DESCRIBE_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration OCR_TIMEOUT = Duration.ofSeconds(90);
    private static final CancellationToken NEVER_CANCELLED = () -> false;
    private static final JsonNode TEXT_SCHEMA = textSchema();

    private static final String DESCRIBE_INSTRUCTIONS = """
            用简洁中文描述每张图片，每张不超过 3 行：主体、明显文字以及与用户提问相关的细节。
            按【图片1】、【图片2】编号。密码、令牌、验证码、Cookie、私钥或会话值必须隐藏。
            不要开场白或总结；无法辨认时写“无法辨认具体内容”。
            """;
    private static final String OCR_INSTRUCTIONS = """
            提取图片中的全部可见文字，按自然阅读顺序输出并尽量保留换行、分段和表格。
            只返回文字；密码、令牌、验证码、Cookie、私钥或会话值替换为“<已隐藏>”。
            没有可识别文字时返回“（未检测到文字）”。
            """;

    private final ModelTaskGateway modelTasks;
    private final RunId ownerRunId;

    public VisionPreprocessor(ModelTaskGateway modelTasks, RunId ownerRunId) {
        this.modelTasks = Objects.requireNonNull(modelTasks, "modelTasks");
        this.ownerRunId = Objects.requireNonNull(ownerRunId, "ownerRunId");
    }

    public String describe(String userInput, List<File> attachments) {
        List<InputBlock> images = new ArrayList<>();
        if (attachments != null) {
            for (File file : attachments) {
                if (file == null || !ProjectAccessPolicy.isProjectFilePath(file.toPath())
                        || !ChatMessage.isImageFile(file)) continue;
                InputBlock image = imageBlock(file);
                if (image != null) images.add(image);
            }
        }
        if (images.isEmpty()) return null;
        String question = userInput == null || userInput.isBlank()
                ? "（用户未输入文字）" : userInput.strip();
        return execute("vision.describe", DESCRIBE_INSTRUCTIONS,
                "用户提问：" + question, images, DESCRIBE_TIMEOUT);
    }

    public String ocrImage(File image) {
        if (image == null || !ProjectAccessPolicy.isProjectFilePath(image.toPath())
                || !image.isFile()) return null;
        InputBlock block = imageBlock(image);
        return block == null ? null : runOcr(block, image.getName());
    }

    /** In-memory entry for already-authorized PDF pages; no temporary file is created. */
    public String ocrImage(BufferedImage image) {
        if (image == null) return null;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", bytes)) return null;
            return runOcr(InputBlock.image("rendered-page.png", bytes.toByteArray(), "image/png"),
                    "内存图像");
        } catch (IOException failure) {
            log.warn("内存图片编码失败", failure);
            return null;
        }
    }

    private String runOcr(InputBlock image, String sourceLabel) {
        return execute("vision.ocr", OCR_INSTRUCTIONS,
                "识别来源：" + sourceLabel, List.of(image), OCR_TIMEOUT);
    }

    private String execute(
            String purpose,
            String instructions,
            String request,
            List<InputBlock> images,
            Duration timeout) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("instructions", instructions);
        input.put("request", request);
        input.put("imageCount", images.size());
        try {
            JsonNode output = modelTasks.execute(new ModelTaskRequest(
                            purpose, ModelTier.NORMAL, input, images, TEXT_SCHEMA, ownerRunId,
                            "vision", timeout, 0, NEVER_CANCELLED, false))
                    .toCompletableFuture().get(timeout.toMillis() + 1_000, TimeUnit.MILLISECONDS)
                    .output();
            String text = output.path("text").asText("").strip();
            if (text.isEmpty()) return null;
            if (SensitiveDataRedactor.containsLikelyCredential(text)) {
                return "（检测到疑似凭据，视觉内容已隐藏）";
            }
            return text;
        } catch (Exception failure) {
            log.warn("{} 失败: {}", purpose, failure.getMessage());
            return null;
        }
    }

    private InputBlock imageBlock(File file) {
        try {
            var path = ProjectAccessPolicy.requireProjectFilePath(file.toPath());
            return InputBlock.image(file.getName(), Files.readAllBytes(path), mediaType(file));
        } catch (IOException | SecurityException failure) {
            log.warn("读取图片失败: {}", file.getName(), failure);
            return null;
        }
    }

    private static String mediaType(File file) {
        return switch (ChatMessage.getFileExtension(file).toLowerCase()) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    private static JsonNode textSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode text = schema.putObject("properties").putObject("text");
        text.put("type", "string");
        schema.putArray("required").add("text");
        schema.put("additionalProperties", false);
        return schema;
    }
}
