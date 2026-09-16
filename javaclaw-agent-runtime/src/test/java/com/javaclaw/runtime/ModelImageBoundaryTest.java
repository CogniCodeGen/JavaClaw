package com.javaclaw.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelImageBoundaryTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.random();
    private static final ThreadId THREAD = ThreadId.random();

    @Test
    void 允许受支持编码的边界尺寸并拒绝超出字节像素或观察身份预算() {
        for (String media : List.of("image/png", "image/jpeg", "image/webp")) {
            assertEquals(
                    "observation",
                    image(media, ModelImage.MAXIMUM_BYTES, 8192, 2048, " observation ")
                            .observationId());
        }
        assertEquals(1, image("image/png", 1, 1, 1, "x".repeat(200)).width());
        assertThrows(IllegalArgumentException.class, () -> image("image/gif", 1, 1, 1, "o"));
        assertThrows(IllegalArgumentException.class, () -> image("image/png", 0, 1, 1, "o"));
        assertThrows(IllegalArgumentException.class, () -> image("image/png", ModelImage.MAXIMUM_BYTES + 1, 1, 1, "o"));
        for (int[] dimensions : List.of(
                new int[] {0, 1}, new int[] {1, 0}, new int[] {8193, 1}, new int[] {1, 8193}, new int[] {4097, 4096})) {
            assertThrows(
                    IllegalArgumentException.class, () -> image("image/png", 1, dimensions[0], dimensions[1], "o"));
        }
        assertThrows(IllegalArgumentException.class, () -> image("image/png", 1, 1, 1, "  "));
        assertThrows(IllegalArgumentException.class, () -> image("image/png", 1, 1, 1, "x".repeat(201)));
    }

    @Test
    void 图片只能作为用户或具备完整调用身份的工具数据且集合不可被后续篡改() {
        ModelImage image = image("image/png", 10, 512, 512, "o");
        ArrayList<ModelImage> pictures = new ArrayList<>(List.of(image, image, image, image));
        ModelMessage user = user(pictures);
        pictures.clear();
        assertEquals(4, user.images().size());
        assertThrows(UnsupportedOperationException.class, () -> user.images().clear());
        assertThrows(IllegalArgumentException.class, () -> user(List.of(image, image, image, image, image)));
        for (MessageRole role : List.of(MessageRole.SYSTEM, MessageRole.ASSISTANT)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ModelMessage(
                            role, "网页数据", List.of(), Optional.empty(), Optional.empty(), List.of(image)));
        }
        ModelMessage tool = new ModelMessage(
                MessageRole.TOOL,
                "观察",
                List.of(),
                Optional.of(" call "),
                Optional.of(" browser_screenshot "),
                List.of(image));
        assertEquals(Optional.of("call"), tool.toolCallId());
        assertEquals(List.of(image), tool.images());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelMessage(
                        MessageRole.TOOL, "观察", List.of(), Optional.of("call"), Optional.of(" "), List.of(image)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelMessage(
                        MessageRole.USER, "伪造回填", List.of(), Optional.empty(), Optional.of("tool"), List.of(image)));
    }

    @Test
    void 旧纯文本构造和缺省图片保持相同调用摘要且无读取能力时明确拒绝() {
        var legacy = new ModelMessage(MessageRole.USER, "旧消息", List.of(), Optional.empty(), Optional.empty());
        var absent = new ModelMessage(MessageRole.USER, "旧消息", List.of(), Optional.empty(), Optional.empty(), null);
        assertEquals(legacy, absent);
        assertEquals(digest(legacy), digest(absent));
        assertEquals(
                "unicode-tools-v1", new ModelContextPolicy(1000, 100, "legacy", "unicode-tools-v1").estimatorVersion());
        assertThrows(
                IllegalArgumentException.class, () -> new ModelContextPolicy(1000, 100, "unknown", "future-estimator"));
        ModelImageResolver unavailable = ModelImageResolver.unavailable();
        assertSame(unavailable, unavailable.forTurn(TurnId.random()));
        assertThrows(NullPointerException.class, () -> unavailable.forTurn(null));
        assertThrows(IllegalStateException.class, () -> unavailable.resolve(image("image/png", 1, 1, 1, "o")));
    }

    @Test
    void 图片按瓦片计入预算且相同内容的新观察或新归属不能复用旧模型调用摘要() {
        ModelImage first = image("image/png", 10, 512, 512, "first");
        ModelImage wider = image("image/png", 10, 513, 512, "first");
        long text = ContextTokenEstimator.messages(List.of(user(List.of())));
        assertEquals(255, ContextTokenEstimator.messages(List.of(user(List.of(first)))) - text);
        assertEquals(425, ContextTokenEstimator.messages(List.of(user(List.of(wider)))) - text);
        assertEquals(510, ContextTokenEstimator.messages(List.of(user(List.of(first, first)))) - text);
        ModelImage later = image("image/png", 10, 512, 512, "later");
        ModelImage otherThread = new ModelImage(first.attachment(), WORKSPACE, ThreadId.random(), "first", 512, 512);
        ModelImage otherWorkspace = new ModelImage(first.attachment(), WorkspaceId.random(), THREAD, "first", 512, 512);
        for (ModelImage changed :
                List.of(wider, later, otherThread, otherWorkspace, image("image/jpeg", 10, 512, 512, "first"))) {
            assertNotEquals(digest(user(List.of(first))), digest(user(List.of(changed))));
        }
        assertNotEquals(digest(user(List.of(first, later))), digest(user(List.of(later, first))));
    }

    @Test
    void 工具图片上限与旧无图片结果保持独立于可信续接标志() {
        var result = new ToolCallResult("call", true, RuntimeFixtures.payload(), Optional.empty());
        ModelImage image = image("image/png", 1, 1, 1, "o");
        var outcome = new ToolExecutionOutcome(result, List.of(), List.of(), List.of(image));
        assertFalse(outcome.yieldTurn());
        assertFalse(ToolExecutionOutcome.resultOnly(result).yieldTurn());
        assertTrue(ToolExecutionOutcome.resultOnly(result).images().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolExecutionOutcome(
                        result, List.of(), List.of(), List.of(image, image, image, image, image)));
    }

    private static ModelImage image(String media, long bytes, int width, int height, String observation) {
        return new ModelImage(
                new AttachmentRef("a".repeat(64), media, "image", bytes),
                WORKSPACE,
                THREAD,
                observation,
                width,
                height);
    }

    private static ModelMessage user(List<ModelImage> images) {
        return new ModelMessage(MessageRole.USER, "页面", List.of(), Optional.empty(), Optional.empty(), images);
    }

    private static String digest(ModelMessage message) {
        return TurnInvocationDigests.model(new ModelInvocation("model", "system", List.of(message), List.of(), 10), 1);
    }
}
