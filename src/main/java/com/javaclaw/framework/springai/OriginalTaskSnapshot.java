package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.ToolInvocationFingerprint;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** 将原任务输入身份绑定到首次投影的正文和媒体，允许安全复用持久化附件快照。 */
final class OriginalTaskSnapshot {
    static final String IDENTITY_METADATA = "javaclaw.originalTaskIdentity";
    static final String CONTENT_METADATA = "javaclaw.originalTaskContent";

    private OriginalTaskSnapshot() { }

    static UserMessage stamp(ReasoningRequest request, UserMessage message) {
        var metadata = new HashMap<>(message.getMetadata());
        metadata.put(SpringAiPromptFactory.ORIGINAL_TASK_METADATA, true);
        metadata.put(IDENTITY_METADATA, identity(request));
        metadata.put(CONTENT_METADATA, contentFingerprint(message));
        return UserMessage.builder().text(message.getText()).media(message.getMedia())
                .metadata(metadata).build();
    }

    static Optional<UserMessage> find(ReasoningRequest request, List<Message> messages) {
        return findLocated(request, messages).map(LocatedSnapshot::message);
    }

    private static Optional<LocatedSnapshot> findLocated(ReasoningRequest request, List<Message> messages) {
        var originals = new ArrayList<LocatedSnapshot>();
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof UserMessage user && SpringAiPromptFactory.isOriginalTask(user)) {
                originals.add(new LocatedSnapshot(index, user));
            }
        }
        if (originals.isEmpty()) return Optional.empty();
        if (originals.size() != 1) throw new IllegalStateException("original task snapshot is duplicated");
        LocatedSnapshot located = originals.getFirst();
        UserMessage message = located.message();
        Object identity = message.getMetadata().get(IDENTITY_METADATA);
        Object content = message.getMetadata().get(CONTENT_METADATA);
        if (identity == null && content == null) {
            UserMessage expected = LegacyAttachmentSnapshot.expected(request, message);
            if (!SpringAiPromptFactory.sameUserContent(message, expected)) {
                throw new IllegalStateException("legacy original task differs from frozen request");
            }
            return Optional.of(new LocatedSnapshot(located.index(), stamp(request, message)));
        }
        if (!identity(request).equals(identity) || !contentFingerprint(message).equals(content)) {
            throw new IllegalStateException("original task snapshot differs from frozen request or content");
        }
        return Optional.of(located);
    }

    static UserMessage require(ReasoningRequest request, List<Message> messages) {
        return requireLocated(request, messages).message();
    }

    /** 仅升级已验真的唯一来源，正文相同的历史消息仍然是历史数据。 */
    static List<Message> restore(ReasoningRequest request, List<Message> messages) {
        LocatedSnapshot original = requireLocated(request, messages);
        var restored = new ArrayList<>(messages);
        restored.set(original.index(), original.message());
        return restored;
    }

    private static LocatedSnapshot requireLocated(ReasoningRequest request, List<Message> messages) {
        Optional<LocatedSnapshot> existing = findLocated(request, messages);
        if (existing.isPresent()) return existing.get();
        if (hasAttachments(request) && request.plan() != null
                && request.plan().descriptor().stepContextPolicy() == null) {
            // 旧的无投影路径没有任务标签，仍须精确匹配可安全重建的正文与媒体。
            var matched = new ArrayList<LocatedSnapshot>();
            for (int index = 0; index < messages.size(); index++) {
                if (messages.get(index) instanceof UserMessage user
                        && !HostContextBlock.owned(user) && !SpringAiPromptFactory.isResumeCommand(user)
                        && SpringAiPromptFactory.sameUserContent(user, LegacyAttachmentSnapshot.expected(request, user))) {
                    matched.add(new LocatedSnapshot(index, user));
                }
            }
            if (matched.size() == 1) {
                LocatedSnapshot legacy = matched.getFirst();
                return new LocatedSnapshot(legacy.index(), stamp(request, legacy.message()));
            }
        }
        throw new IllegalStateException("original task snapshot is missing");
    }

    private record LocatedSnapshot(int index, UserMessage message) { }

    static boolean hasAttachments(ReasoningRequest request) {
        return request.runRequest().inputs().stream().anyMatch(block ->
                block.type().equals("core.file") || block.type().equals("core.image")
                        || block.type().equals("core.audio"));
    }

    private static String identity(ReasoningRequest request) {
        var value = JsonNodeFactory.instance.objectNode()
                .put("effectiveTask", SpringAiPromptFactory.effectiveTaskText(request));
        var inputs = value.putArray("inputs");
        request.runRequest().inputs().forEach(block -> inputs.addObject()
                .put("type", block.type()).put("data", ToolInvocationFingerprint.create(
                        block.type(), block.data())));
        return ToolInvocationFingerprint.create("original-task-input-v1", value);
    }

    static String contentFingerprint(UserMessage message) {
        var value = JsonNodeFactory.instance.objectNode().put("text", message.getText());
        var media = value.putArray("media");
        for (Media item : message.getMedia()) {
            var serialized = media.addObject().put("name", item.getName())
                    .put("mimeType", item.getMimeType().toString()).put("id", item.getId());
            if (item.getData() instanceof byte[] bytes) serialized.put("bytes", digest(bytes));
            else serialized.put("uri", item.getData().toString());
        }
        return ToolInvocationFingerprint.create("original-task-content-v1", value);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
