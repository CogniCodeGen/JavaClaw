package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunSnapshot;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.core.ExecutionPlan;
import com.javaclaw.framework.core.ExecutionPlanDescriptor;
import com.javaclaw.framework.core.ReasoningEventSink;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.StepContextPolicy;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.extension.ExtensionRegistrySnapshot;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;
import com.javaclaw.util.ProjectAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginalTaskSnapshotTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<ExecutionPlan> plans = new ArrayList<>();
    private ExtensionManager extensions;
    private Path fixtures;

    @BeforeEach
    void 创建项目附件与空扩展计划() throws Exception {
        Path target = ProjectAccessPolicy.projectRoot().resolve("target");
        Files.createDirectories(target);
        fixtures = Files.createTempDirectory(target, "original-task-snapshot-");
        extensions = new ExtensionManager(new ExtensionContext(Clock.systemUTC(), Runnable::run,
                ignored -> CompletableFuture.failedFuture(new AssertionError("不应调用辅助模型"))));
    }

    @AfterEach
    void 清理附件并释放计划() throws Exception {
        plans.forEach(ExecutionPlan::close);
        extensions.close();
        try (var paths = Files.walk(fixtures)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void 同轮附件修改或删除后复用首次正文媒体并严格验证Provider快照() throws Exception {
        Path text = Files.writeString(fixtures.resolve("notes.txt"), "首次正文");
        Path image = Files.write(fixtures.resolve("picture.png"), new byte[]{1, 2, 3});
        ReasoningRequest request = request(List.of(InputBlock.text("处理附件"),
                file(text, "text/plain"), file(image, "image/png")), null);
        UserMessage original = SpringAiPromptFactory.originalTaskMessage(request);
        Files.writeString(text, "修改后的正文");
        Files.delete(image);

        assertSame(original, SpringAiPromptFactory.originalTaskMessage(request, List.of(original)));
        assertTrue(original.getText().contains("首次正文"));
        assertArrayEquals(new byte[]{1, 2, 3}, original.getMedia().getFirst().getDataAsByteArray());
        UserMessage recovered = (UserMessage) StepMessageCodec.message(StepMessageCodec.message(original));
        StepContextProjector projector = new StepContextProjector(StepContextPolicy.DEFAULT, json);
        projector.validate(List.of(recovered), request, List.of(original));

        UserMessage changed = OriginalTaskSnapshot.stamp(request,
                UserMessage.builder().text("替换正文").media(original.getMedia()).build());
        assertThrows(IllegalStateException.class, () -> projector.validate(List.of(changed), request, List.of(original)));
        UserMessage changedMedia = OriginalTaskSnapshot.stamp(request,
                UserMessage.builder().text(original.getText()).media(Media.builder()
                        .name(original.getMedia().getFirst().getName()).mimeType(MimeTypeUtils.IMAGE_PNG)
                        .data(new byte[]{9}).build()).build());
        assertThrows(IllegalStateException.class, () -> projector.validate(List.of(changedMedia), request, List.of(original)));
    }

    @Test
    void 快照身份拒绝不同冻结输入且拒绝直接改写正文或字节() throws Exception {
        Path file = Files.writeString(fixtures.resolve("input.txt"), "原始正文");
        ReasoningRequest request = request(List.of(InputBlock.text("原任务"), file(file, "text/plain")), null);
        UserMessage original = SpringAiPromptFactory.originalTaskMessage(request);
        ReasoningRequest different = request(List.of(InputBlock.text("另一任务"), file(file, "text/plain")), null);

        assertThrows(IllegalStateException.class, () -> OriginalTaskSnapshot.require(different, List.of(original)));
        UserMessage corrupted = UserMessage.builder().text("篡改正文")
                .metadata(original.getMetadata()).media(original.getMedia()).build();
        assertThrows(IllegalStateException.class, () -> OriginalTaskSnapshot.require(request, List.of(corrupted)));
    }

    @Test
    void 恢复Journal使用已持久化附件快照且缺失本机文件不影响继续() throws Exception {
        Path text = Files.writeString(fixtures.resolve("durable.txt"), "已持久化正文");
        Path image = Files.write(fixtures.resolve("durable.png"), new byte[]{4, 5, 6});
        ReasoningRequest request = request(List.of(InputBlock.text("任务"),
                file(text, "text/plain"), file(image, "image/png")), 计划(null));
        JournalFixture fixture = new JournalFixture(request);
        UserMessage original = SpringAiPromptFactory.originalTaskMessage(request);
        ModelStepJournal journal = new ModelStepJournal(fixture.reasoning, fixture.store, json);
        var model = journal.started(new Prompt(List.of(new SystemMessage("system"), original)), 1, null);
        journal.completed(model, new ChatResponse(List.of(new Generation(new AssistantMessage("结果")))));
        Files.delete(text);
        Files.delete(image);

        var restored = new ModelStepJournal(fixture.reasoning, fixture.store, json)
                .recover(List.of(), ignored -> CompletableFuture.failedFuture(new AssertionError("不应执行工具")), false);
        UserMessage recovered = OriginalTaskSnapshot.require(fixture.reasoning, restored.providerMessages());
        assertTrue(SpringAiPromptFactory.sameUserContent(original, recovered));
        assertTrue(recovered.getText().contains("已持久化正文"));
        assertArrayEquals(new byte[]{4, 5, 6}, recovered.getMedia().getFirst().getDataAsByteArray());
        assertEquals("结果", restored.finalResponse().getResult().getOutput().getText());
    }

    @Test
    void 恢复有标记快照不把同文的普通历史升级为原任务() throws Exception {
        for (boolean stamped : List.of(true, false)) {
            ReasoningRequest request = request(List.of(InputBlock.text("生成一个java项目，使用javafx实现一个贪吃蛇")),
                    计划(StepContextPolicy.DEFAULT));
            JournalFixture fixture = new JournalFixture(request);
            UserMessage original = SpringAiPromptFactory.originalTaskMessage(fixture.reasoning);
            if (!stamped) {
                original = UserMessage.builder().text(original.getText())
                        .metadata(Map.of(SpringAiPromptFactory.ORIGINAL_TASK_METADATA, true)).build();
            }
            Message selectedHistory = new StepContextAssembler("history",
                    new StepContextProjector(StepContextPolicy.DEFAULT, json))
                    .selected(List.of(new UserMessage(original.getText()))).getFirst();
            UserMessage historyWithMedia = UserMessage.builder().text(original.getText())
                    .media(Media.builder().name("history.png").mimeType(MimeTypeUtils.IMAGE_PNG)
                            .data(new byte[]{9}).build()).build();
            List<Message> persisted = List.of(new SystemMessage("system"), selectedHistory, historyWithMedia, original);
            ModelStepJournal journal = new ModelStepJournal(fixture.reasoning, fixture.store, json);
            var model = journal.started(new Prompt(persisted), 1, null);
            journal.completed(model, new ChatResponse(List.of(new Generation(new AssistantMessage("结果")))));

            var restored = new ModelStepJournal(fixture.reasoning, fixture.store, json)
                    .recover(List.of(), ignored -> CompletableFuture.failedFuture(new AssertionError("不应执行工具")), false);

            assertEquals(1, restored.providerMessages().stream().filter(message -> message instanceof UserMessage user
                    && SpringAiPromptFactory.isOriginalTask(user)).count());
            assertEquals(StepMessageCodec.message(selectedHistory), StepMessageCodec.message(restored.providerMessages().get(1)));
            assertEquals(StepMessageCodec.message(historyWithMedia), StepMessageCodec.message(restored.providerMessages().get(2)));
            UserMessage recovered = OriginalTaskSnapshot.require(fixture.reasoning, restored.providerMessages());
            assertTrue(recovered.getMetadata().containsKey(OriginalTaskSnapshot.IDENTITY_METADATA));
            assertTrue(SpringAiPromptFactory.sameUserContent(original, recovered));
            assertTrue(SpringAiPromptFactory.sameUserContent(recovered,
                    SpringAiPromptFactory.originalTaskMessage(fixture.reasoning, restored.providerMessages())));
            new StepContextProjector(StepContextPolicy.DEFAULT, json)
                    .validate(restored.providerMessages(), fixture.reasoning, restored.providerMessages());
        }
    }

    @Test
    void 恢复无标记旧附件只升级唯一未包装来源并拒绝歧义() throws Exception {
        ReasoningRequest request = request(List.of(InputBlock.text("任务"),
                InputBlock.file("remote", "https://example.test/image.png", "image/png")), 计划(null));
        UserMessage legacy = UserMessage.builder().text("任务\n[Attachment: remote; image/png]")
                .media(Media.builder().name("remote").mimeType(MimeTypeUtils.IMAGE_PNG)
                        .data(java.net.URI.create("https://example.test/image.png")).build()).build();
        Message selectedHistory = new StepContextAssembler("history", new StepContextProjector(null, json))
                .selected(List.of(legacy)).getFirst();
        JournalFixture fixture = new JournalFixture(request);
        ModelStepJournal journal = new ModelStepJournal(fixture.reasoning, fixture.store, json);
        var model = journal.started(new Prompt(List.of(new SystemMessage("system"), selectedHistory, legacy)), 1, null);
        journal.completed(model, new ChatResponse(List.of(new Generation(new AssistantMessage("结果")))));

        var restored = new ModelStepJournal(fixture.reasoning, fixture.store, json)
                .recover(List.of(), ignored -> CompletableFuture.failedFuture(new AssertionError("不应执行工具")), false);

        assertEquals(StepMessageCodec.message(selectedHistory), StepMessageCodec.message(restored.providerMessages().get(1)));
        assertFalse(SpringAiPromptFactory.isOriginalTask((UserMessage) restored.providerMessages().get(1)));
        UserMessage recovered = OriginalTaskSnapshot.require(fixture.reasoning, restored.providerMessages());
        assertTrue(SpringAiPromptFactory.sameUserContent(legacy, recovered));
        assertEquals(1, restored.providerMessages().stream().filter(message -> message instanceof UserMessage user
                && SpringAiPromptFactory.isOriginalTask(user)).count());
        assertThrows(IllegalStateException.class,
                () -> OriginalTaskSnapshot.restore(fixture.reasoning, List.of(legacy, legacy)));
    }

    @Test
    void 真正两份原任务快照仍在恢复和Provider边界拒绝() throws Exception {
        ReasoningRequest request = request(List.of(InputBlock.text("原任务")), 计划(StepContextPolicy.DEFAULT));
        UserMessage original = SpringAiPromptFactory.originalTaskMessage(request);
        ReasoningRequest different = request(List.of(InputBlock.text("其他任务")), request.plan());
        UserMessage other = SpringAiPromptFactory.originalTaskMessage(different);
        StepContextProjector projector = new StepContextProjector(StepContextPolicy.DEFAULT, json);
        for (UserMessage second : List.of(original, other)) {
            JournalFixture fixture = new JournalFixture(request);
            ModelStepJournal journal = new ModelStepJournal(fixture.reasoning, fixture.store, json);
            List<Message> duplicated = List.of(new SystemMessage("system"), original, second);
            var model = journal.started(new Prompt(duplicated), 1, null);
            journal.completed(model, new ChatResponse(List.of(new Generation(new AssistantMessage("结果")))));

            assertThrows(ToolRecoveryRequiredException.class, () ->
                    new ModelStepJournal(fixture.reasoning, fixture.store, json)
                            .recover(List.of(), ignored -> CompletableFuture.failedFuture(new AssertionError("不应执行工具")), false));
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> projector.validate(duplicated, request, List.of(original))).getMessage().contains("duplicated"));
        }
    }

    @Test
    void 旧远程和内嵌媒体经精确验证升级而旧本机附件要求重新开始() throws Exception {
        byte[] bytes = new byte[]{3, 2, 1};
        for (InputBlock attachment : List.of(InputBlock.file("remote", "https://example.test/image.png", "image/png"),
                InputBlock.image("inline", bytes, "image/png"))) {
            ReasoningRequest request = request(List.of(InputBlock.text("任务"), attachment), null);
            Object data = attachment.data().has("uri") ? java.net.URI.create(attachment.data().path("uri").asText()) : bytes;
            Media.Builder builder = Media.builder().name(attachment.data().path("name").asText()).mimeType(MimeTypeUtils.IMAGE_PNG);
            if (data instanceof java.net.URI uri) builder.data(uri);
            else builder.data(data);
            UserMessage legacy = UserMessage.builder()
                    .text("任务\n[Attachment: " + attachment.data().path("name").asText() + "; image/png]")
                    .media(builder.build()).metadata(Map.of(SpringAiPromptFactory.ORIGINAL_TASK_METADATA, true)).build();
            UserMessage upgraded = OriginalTaskSnapshot.require(request, List.of(legacy));
            assertTrue(upgraded.getMetadata().containsKey(OriginalTaskSnapshot.IDENTITY_METADATA));
            assertTrue(SpringAiPromptFactory.sameUserContent(legacy, upgraded));
            OriginalTaskSnapshot.require(request, List.of((UserMessage) StepMessageCodec.message(StepMessageCodec.message(upgraded))));
        }
        Path local = Files.write(fixtures.resolve("local.png"), bytes);
        ReasoningRequest request = request(List.of(InputBlock.text("任务"), file(local, "image/png")), null);
        UserMessage legacy = UserMessage.builder().text("任务\n[Attachment: local.png; image/png]")
                .media(Media.builder().name("local.png").mimeType(MimeTypeUtils.IMAGE_PNG).data(local.toUri()).build())
                .metadata(Map.of(SpringAiPromptFactory.ORIGINAL_TASK_METADATA, true)).build();
        assertTrue(assertThrows(IllegalStateException.class,
                () -> OriginalTaskSnapshot.require(request, List.of(legacy))).getMessage().contains("restart"));
    }

    @Test
    void 缺少快照身份的旧本机附件在恢复前明确暂停() throws Exception {
        Path file = Files.writeString(fixtures.resolve("legacy.txt"), "正文");
        ReasoningRequest request = request(List.of(InputBlock.text("任务"), file(file, "text/plain")), 计划(null));
        JournalFixture fixture = new JournalFixture(request);
        ModelStepJournal journal = new ModelStepJournal(fixture.reasoning, fixture.store, json);
        UserMessage legacy = new UserMessage("任务\n[Attachment: legacy.txt; text/plain]");
        var model = journal.started(new Prompt(legacy), 1, null);
        journal.completed(model, new ChatResponse(List.of(new Generation(new AssistantMessage("结果")))));

        assertThrows(ToolRecoveryRequiredException.class, () -> new ModelStepJournal(fixture.reasoning, fixture.store, json)
                .recover(List.of(), ignored -> CompletableFuture.failedFuture(new AssertionError("不应执行工具")), false));
    }

    @Test
    void 大量换行引号的长附件按最终JSON块预算截断并留出默认上下文空间() throws Exception {
        Path file = Files.writeString(fixtures.resolve("escaped.txt"), "\n\"\\".repeat(25_000));
        ReasoningRequest request = request(List.of(InputBlock.text("处理附件"), file(file, "text/plain")), 计划(StepContextPolicy.DEFAULT));
        UserMessage original = SpringAiPromptFactory.originalTaskMessage(request);

        assertTrue(original.getText().length() <= StepContextPolicy.DEFAULT.maxMessageCharacters() / 2);
        assertTrue(original.getText().contains("截断"));
        List<Message> messages = List.of(new SystemMessage("s".repeat(8_000)), original);
        StepContextProjector projector = new StepContextProjector(StepContextPolicy.DEFAULT, json);
        projector.validate(projector.project(messages).messages(), request, messages);
    }

    private ReasoningRequest request(List<InputBlock> inputs, ExecutionPlan plan) {
        ReasoningRequest base = SpringAiPromptFactoryAttachmentTest.request(inputs);
        return new ReasoningRequest(new RunId("attachment-snapshot-run"), plan, base.runRequest(), null, null, null);
    }

    private static InputBlock file(Path path, String mediaType) {
        return InputBlock.file(path.getFileName().toString(), path.toUri().toString(), mediaType);
    }

    private ExecutionPlan 计划(StepContextPolicy policy) throws Exception {
        var descriptor = new ExecutionPlanDescriptor("plan", AgentDefinitionRef.latest("agent"),
                RunProfileRef.latest("profile"), "definition", "profile", 1, List.of(), "model",
                Map.of(), "prompt", Map.of(), JsonNodeFactory.instance.objectNode(), PermissionSet.NONE,
                RunBudget.UNBOUNDED, policy, null, List.of(), List.of(), JsonNodeFactory.instance.objectNode(), "checksum");
        Constructor<ExecutionPlan> constructor = ExecutionPlan.class.getDeclaredConstructor(
                ExecutionPlanDescriptor.class, ExtensionRegistrySnapshot.SnapshotLease.class);
        constructor.setAccessible(true);
        ExecutionPlan plan = constructor.newInstance(descriptor, extensions.acquireCurrent());
        plans.add(plan);
        return plan;
    }

    private static final class JournalFixture {
        private final List<RunEventEnvelope> events = new ArrayList<>();
        private final RunStore store;
        private final ReasoningRequest reasoning;

        private JournalFixture(ReasoningRequest request) {
            Instant now = Instant.now();
            StoredRun stored = new StoredRun(new RunSnapshot(request.runId(), RunState.RUNNING,
                    request.plan().descriptor().id(), 0, now, now, null, null, 1), request.runRequest());
            store = (RunStore) Proxy.newProxyInstance(RunStore.class.getClassLoader(), new Class<?>[]{RunStore.class},
                    (ignored, method, arguments) -> switch (method.getName()) {
                        case "find" -> Optional.of(stored);
                        case "readable" -> true;
                        case "eventsAfter" -> events.stream().filter(event -> event.sequence() > (long) arguments[1]).toList();
                        default -> throw new AssertionError("非预期调用：" + method.getName());
                    });
            ReasoningEventSink sink = (type, version, producer, payload) -> events.add(new RunEventEnvelope(
                    request.runId().value(), events.size() + 1L, Instant.now(), type, version, producer,
                    null, null, payload));
            reasoning = new ReasoningRequest(request.runId(), request.plan(), request.runRequest(), null, null, sink);
        }
    }
}
