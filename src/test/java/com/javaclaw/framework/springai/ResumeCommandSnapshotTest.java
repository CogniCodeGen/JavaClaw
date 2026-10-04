package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.util.ProjectAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeCommandSnapshotTest {
    private static final RunId RUN = new RunId("resume-attachments");
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5V8AAAAASUVORK5CYII=");
    private final List<RunEventEnvelope> events = new ArrayList<>();
    private Path directory;

    @BeforeEach
    void 创建项目内隔离附件目录() throws IOException {
        Path target = ProjectAccessPolicy.projectRoot().resolve("target");
        Files.createDirectories(target);
        directory = Files.createTempDirectory(target, "resume-attachment-test-");
    }

    @AfterEach
    void 仅清理本测试创建的目录() throws IOException {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void 续答附件包含文本正文和图片字节而不是本地URI() throws IOException {
        Path text = Files.writeString(directory.resolve("说明.txt"), "仅用于附件续答的文本正文");
        Path image = Files.write(directory.resolve("图片.png"), PNG);
        ReasoningRequest request = request(text, image);
        resumed(request, 1);

        UserMessage message = project(request, List.of());

        assertTrue(message.getText().contains("仅用于附件续答的文本正文"));
        assertTrue(message.getText().contains("请分析补充附件"));
        assertTrue(SpringAiPromptFactory.isResumeCommand(message));
        assertFalse(SpringAiPromptFactory.isOriginalTask(message));
        assertEquals(1, message.getMedia().size());
        assertArrayEquals(PNG, assertInstanceOf(byte[].class, message.getMedia().getFirst().getData()));
    }

    @Test
    void 同一恢复事件在文件修改或删除后复用持久化快照() throws IOException {
        Path text = Files.writeString(directory.resolve("说明.txt"), "初次提交正文");
        Path image = Files.write(directory.resolve("图片.png"), PNG);
        ReasoningRequest request = request(text, image);
        resumed(request, 1);
        UserMessage original = project(request, List.of());
        List<Message> persisted = StepMessageCodec.messages(StepMessageCodec.messages(List.of(original)));
        Files.writeString(text, "工具已修改这个文件");
        Files.delete(image);

        UserMessage recovered = project(request, persisted);

        assertEquals(StepMessageCodec.message(original), StepMessageCodec.message(recovered));
        assertTrue(recovered.getText().contains("初次提交正文"));
        assertFalse(recovered.getText().contains("工具已修改这个文件"));
        assertArrayEquals(PNG, assertInstanceOf(byte[].class, recovered.getMedia().getFirst().getData()));
    }

    @Test
    void 相同payload的新恢复事件重新读取用户再次提交的附件() throws IOException {
        Path text = Files.writeString(directory.resolve("说明.txt"), "第一次提交的正文");
        Path image = Files.write(directory.resolve("图片.png"), PNG);
        ReasoningRequest request = request(text, image);
        resumed(request, 1);
        UserMessage first = project(request, List.of());
        Files.writeString(text, "第二次明确提交的新正文");
        resumed(request, 2);

        UserMessage second = project(request, List.of(first));

        assertTrue(second.getText().contains("第二次明确提交的新正文"));
        assertFalse(second.getText().contains("第一次提交的正文"));
        assertFalse(StepMessageCodec.message(first).equals(StepMessageCodec.message(second)));
    }

    @Test
    void 修改快照正文或媒体后拒绝以旧摘要继续投递() throws IOException {
        Path text = Files.writeString(directory.resolve("说明.txt"), "原正文");
        Path image = Files.write(directory.resolve("图片.png"), PNG);
        ReasoningRequest request = request(text, image);
        resumed(request, 1);
        UserMessage original = project(request, List.of());
        UserMessage textChanged = UserMessage.builder().text("伪造正文")
                .media(original.getMedia()).metadata(original.getMetadata()).build();
        assertThrows(IllegalStateException.class, () -> project(request, List.of(textChanged)));

        Media originalMedia = original.getMedia().getFirst();
        Media changedMedia = Media.builder().name(originalMedia.getName())
                .mimeType(originalMedia.getMimeType()).data(new byte[] {1, 2, 3}).build();
        UserMessage mediaChanged = UserMessage.builder().text(original.getText())
                .media(List.of(changedMedia)).metadata(original.getMetadata()).build();
        assertThrows(IllegalStateException.class, () -> project(request, List.of(mediaChanged)));
    }

    @Test
    void 旧版纯文本续答仍可按完整命令正文校验() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("text", "Markdown");
        payload.putArray("inputs").addObject().put("type", "core.text")
                .putObject("data").put("text", "Markdown");
        ResumeCommand command = new ResumeCommand("input", payload);
        ReasoningRequest request = request(command);
        UserMessage legacy = UserMessage.builder().text("Resume command (input): " + payload)
                .metadata(Map.of(SpringAiPromptFactory.RESUME_COMMAND_METADATA, true)).build();

        assertEquals(legacy, ResumeCommandSnapshot.requireCurrent(request, List.of(legacy)));
    }

    private ReasoningRequest request(Path text, Path image) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("text", "请分析补充附件");
        var inputs = payload.putArray("inputs");
        inputs.addObject().put("type", "core.text").putObject("data").put("text", "请分析补充附件");
        inputs.addObject().put("type", "core.file").putObject("data")
                .put("name", text.getFileName().toString()).put("mediaType", "text/plain")
                .put("uri", text.toUri().toString());
        inputs.addObject().put("type", "core.image").putObject("data")
                .put("name", image.getFileName().toString()).put("mediaType", "image/png")
                .put("uri", image.toUri().toString());
        return request(new ResumeCommand("input", payload));
    }

    private ReasoningRequest request(ResumeCommand command) {
        RunRequest run = RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "resume-session"))
                .input(InputBlock.text("原任务")).build();
        return new ReasoningRequest(RUN, null, run, command, null, null);
    }

    private void resumed(ReasoningRequest request, long sequence) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("commandType", request.resumeCommand().type());
        payload.set("command", request.resumeCommand().payload());
        events.add(new RunEventEnvelope(RUN.value(), sequence, Instant.EPOCH,
                "core.run.resumed", 1, "framework.core", null, null, payload));
    }

    private UserMessage project(ReasoningRequest request, List<Message> incoming) {
        RunStore runs = (RunStore) Proxy.newProxyInstance(RunStore.class.getClassLoader(),
                new Class<?>[] {RunStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("eventsAfter")) return List.copyOf(events);
                    throw new UnsupportedOperationException(method.getName());
                });
        return SpringAiPromptFactory.resumeCommandMessage(request, incoming, runs);
    }
}
