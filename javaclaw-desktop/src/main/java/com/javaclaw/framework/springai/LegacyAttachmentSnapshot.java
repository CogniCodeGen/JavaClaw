package com.javaclaw.framework.springai;

import com.javaclaw.framework.core.ReasoningRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 旧附件只允许从冻结远程引用或内嵌字节验证升级，绝不重新读取本地文件。 */
final class LegacyAttachmentSnapshot {
    private LegacyAttachmentSnapshot() { }

    static UserMessage expected(ReasoningRequest request, UserMessage persisted) {
        try {
            return rebuild(request, persisted);
        } catch (IllegalStateException rejected) {
            throw rejected;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("legacy attachment snapshot cannot be safely verified; restart the Run", invalid);
        }
    }

    private static UserMessage rebuild(ReasoningRequest request, UserMessage persisted) {
        StringBuilder text = new StringBuilder(SpringAiPromptFactory.originalTaskText(request));
        var media = new ArrayList<Media>();
        int remainingBytes = SpringAiAttachmentReader.MAX_TOTAL_BYTES;
        for (var block : request.runRequest().inputs()) {
            if (!Set.of("core.file", "core.image", "core.audio").contains(block.type())) continue;
            var data = block.data();
            String name = data.path("name").asText("attachment");
            String mediaType = data.path("mediaType").asText("application/octet-stream");
            if (!(mediaType.startsWith("image/") || mediaType.startsWith("audio/"))) throw restart();
            Media.Builder builder = Media.builder().name(name).mimeType(MimeTypeUtils.parseMimeType(mediaType));
            String inline = data.path("base64").asText("");
            if (!inline.isBlank()) {
                if (inline.length() > 4L * ((remainingBytes + 2L) / 3L)) throw restart();
                byte[] bytes = Base64.getDecoder().decode(inline);
                if (bytes.length > remainingBytes) throw restart();
                remainingBytes -= bytes.length;
                builder.data(bytes);
            } else {
                URI uri = URI.create(data.path("uri").asText(""));
                if (!Set.of("http", "https", "data").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT))) throw restart();
                builder.data(uri);
            }
            media.add(builder.build());
            text.append("\n[Attachment: ").append(name).append("; ").append(mediaType).append(']');
        }
        UserMessage expected = UserMessage.builder().text(text.toString()).media(media)
                .metadata(Map.of(SpringAiPromptFactory.ORIGINAL_TASK_METADATA, true)).build();
        if (!SpringAiPromptFactory.sameUserContent(persisted, expected)
                && request.plan() != null && request.plan().descriptor().stepContextPolicy() == null
                && request.resumeCommand() != null && !request.resumeCommand().type().equals("tool.approval")) {
            // 旧的无投影消息合并恢复命令；验证后仍保留其首次正文，不再次拼接。
            text.append("\n\nResume command (").append(request.resumeCommand().type())
                    .append("): ").append(request.resumeCommand().payload());
            expected = UserMessage.builder().text(text.toString()).media(media)
                    .metadata(Map.of(SpringAiPromptFactory.ORIGINAL_TASK_METADATA, true)).build();
        }
        return expected;
    }

    private static IllegalStateException restart() {
        return new IllegalStateException("legacy local attachment prompt has no frozen snapshot; restart the Run");
    }
}
