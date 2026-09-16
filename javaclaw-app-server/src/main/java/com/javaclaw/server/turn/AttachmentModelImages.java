package com.javaclaw.server.turn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelImage;
import com.javaclaw.runtime.ModelImageResolver;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CoreCommandService;

/** 图片投影与读取的宿主边界；内容摘要只标识文件，Thread 的持久引用才证明使用资格。 */
public final class AttachmentModelImages implements ModelImageResolver {
    private final CoreCommandService core;
    private final AttachmentService attachments;
    private final CanonicalJson json;

    /**
     * 创建不联网的图片投影服务。
     *
     * @param core Thread 与 Item 权威查询
     * @param attachments Workspace 附件存储
     * @param json 共享 codec
     */
    public AttachmentModelImages(CoreCommandService core, AttachmentService attachments, CanonicalJson json) {
        this.core = Objects.requireNonNull(core, "core");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public ModelImageResolver forTurn(com.javaclaw.api.TurnId turnId) {
        Objects.requireNonNull(turnId, "turnId");
        // 延迟到真正读取图片时复核；仅文本的 Provider 配置探测不需要持久 Turn。
        return image -> {
            var turn = core.findTurn(turnId).orElseThrow(() -> new SecurityException("图片请求没有可信 Turn"));
            if (!turn.threadId().equals(image.threadId())) {
                throw new SecurityException("图片引用不属于正在执行的 Turn 对话");
            }
            return resolve(image);
        };
    }

    @Override
    public byte[] resolve(ModelImage image) {
        requireOwner(image.workspaceId(), image.threadId());
        if (!referenced(image)) {
            throw new SecurityException("图片没有当前 Thread 的已提交引用");
        }
        byte[] bytes = read(image.workspaceId(), image.attachment());
        Dimensions dimensions = dimensions(bytes);
        if (dimensions.width() != image.width() || dimensions.height() != image.height()) {
            throw new SecurityException("图片尺寸与观察帧不一致");
        }
        return bytes;
    }

    /**
     * 把用户消息附件投影为图片；只读取受预算约束且可识别的 PNG/JPEG 等格式。
     *
     * @param workspace 当前 Workspace
     * @param thread 当前 Thread
     * @param item 持久消息 Item
     * @param message 已解码消息
     * @return 有界图片列表
     */
    public List<ModelImage> message(
            WorkspaceId workspace, ThreadId thread, ItemEnvelope item, CorePayloads.Message message) {
        requireOwner(workspace, thread);
        if (message.role() != com.javaclaw.api.MessageRole.USER
                || item.status() != ItemStatus.COMPLETED
                || !core.findTurn(item.turnId()).orElseThrow().threadId().equals(thread)) {
            throw new SecurityException("图片输入必须来自当前对话的已提交用户消息");
        }
        List<ModelImage> images = new ArrayList<>();
        for (AttachmentRef reference : message.attachments()) {
            if (reference.mediaType().startsWith("image/") && reference.sizeBytes() <= ModelImage.MAXIMUM_BYTES) {
                Dimensions dimensions = dimensions(read(workspace, reference));
                images.add(new ModelImage(
                        reference,
                        workspace,
                        thread,
                        item.id() + ":" + reference.digest(),
                        dimensions.width(),
                        dimensions.height()));
            }
        }
        if (images.size() > 4) {
            throw new IllegalArgumentException("单条消息最多向模型发送四张图片");
        }
        return List.copyOf(images);
    }

    private boolean referenced(ModelImage image) {
        List<ItemEnvelope> items = core.listItems(image.threadId());
        Map<CallKey, CallSource> calls = calls(items);
        for (ItemEnvelope item : items) {
            if (item.status() != ItemStatus.COMPLETED || !"core".equals(item.producerId())) {
                continue;
            }
            if (CoreSchemas.MESSAGE.equals(item.schemaId())) {
                var message = json.decode(item.payload(), CorePayloads.Message.class);
                if (message.role() == com.javaclaw.api.MessageRole.USER
                        && message.attachments().contains(image.attachment())
                        && image.observationId()
                                .equals(item.id() + ":" + image.attachment().digest())) {
                    return true;
                }
            } else if (CoreSchemas.TOOL_RESULT.equals(item.schemaId()) && browserReference(item, calls, image)) {
                return true;
            }
        }
        return false;
    }

    private Map<CallKey, CallSource> calls(List<ItemEnvelope> items) {
        Map<CallKey, CallSource> calls = new HashMap<>();
        Set<CallKey> duplicates = new HashSet<>();
        for (ItemEnvelope item : items) {
            if (!"core".equals(item.producerId()) || !CoreSchemas.TOOL_CALL.equals(item.schemaId())) {
                continue;
            }
            var call = json.decode(item.payload(), CorePayloads.ToolCall.class);
            var key = new CallKey(item.turnId(), call.callId());
            if (calls.putIfAbsent(key, new CallSource(item.sequence(), item.status(), call)) != null) {
                duplicates.add(key);
            }
        }
        // 读取完整历史后再签发图片权限，避免结果后到的重复调用身份绕过唯一性校验。
        duplicates.forEach(calls::remove);
        return calls;
    }

    private boolean browserReference(ItemEnvelope item, Map<CallKey, CallSource> calls, ModelImage image) {
        var result = json.decode(item.payload(), CorePayloads.ToolResult.class);
        CallSource source = calls.get(new CallKey(item.turnId(), result.callId()));
        if (!result.success()
                || source == null
                || source.status() != ItemStatus.COMPLETED
                || source.sequence() >= item.sequence()) {
            return false;
        }
        CorePayloads.ToolCall call = source.call();
        return BrowserImageProjection.project(
                        json,
                        image.workspaceId(),
                        image.threadId(),
                        new ToolIdentity(call.producerId(), call.toolName(), call.toolRevision()),
                        result.output())
                .contains(image);
    }

    private void requireOwner(WorkspaceId workspace, ThreadId thread) {
        if (!core.workspaceForThread(thread).id().equals(workspace)) {
            throw new SecurityException("图片 Thread 与 Workspace 不一致");
        }
    }

    private byte[] read(WorkspaceId workspace, AttachmentRef reference) {
        attachments.requireOwned(workspace, reference);
        if (reference.sizeBytes() < 1 || reference.sizeBytes() > ModelImage.MAXIMUM_BYTES) {
            throw new IllegalArgumentException("图片字节预算超限");
        }
        return attachments
                .read(AttachmentScope.workspace(workspace), reference.digest())
                .content();
    }

    private static Dimensions dimensions(byte[] bytes) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("当前平台无法识别该图片格式，请使用 PNG 或 JPEG");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new Dimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("图片头部无效", failure);
        }
    }

    private record Dimensions(int width, int height) {}

    private record CallKey(TurnId turnId, String callId) {}

    private record CallSource(long sequence, ItemStatus status, CorePayloads.ToolCall call) {}
}
