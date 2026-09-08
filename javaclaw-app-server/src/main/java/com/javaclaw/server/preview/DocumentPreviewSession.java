package com.javaclaw.server.preview;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.DocumentPreviewRpcContracts;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolException;
import com.javaclaw.protocol.WriteCommand;

/**
 * 单连接预览句柄和幂等结果的所有者；只读内容不会写入领域 revision 或重用另一连接的资源。
 *
 * <p>同键重试只重放原回执，不续租或重新激活已经关闭的句柄。业务请求由单 reader 串行路由，关闭可并发发布取消。
 */
public final class DocumentPreviewSession implements AutoCloseable {
    private static final int MAXIMUM_RECEIPTS = 8192;
    private final DocumentPreviewService service;
    private final CanonicalJson json;
    private final String id;
    private final CancellationSource cancellation = new CancellationSource();
    private final Map<String, Receipt> receipts = new HashMap<>();

    DocumentPreviewSession(DocumentPreviewService service, CanonicalJson json, String id) {
        this.service = service;
        this.json = json;
        this.id = id;
    }

    /**
     * 绑定完成能力协商的有界通知队列，回调不得阻塞或执行 IO。
     *
     * @param listener 当前连接的失效通知入队器
     */
    public void notifications(Consumer<DocumentPreviewRpcContracts.Invalidated> listener) {
        service.notifications(id, Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 在能力及 WriteCommand 校验后执行预览请求。
     *
     * @param method 已注册的预览方法
     * @param params 规范请求
     * @return 不含宿主路径的规范结果
     * @throws Exception 权限撤销、非法来源或 IO 失败
     */
    public CanonicalPayload handle(String method, CanonicalPayload params) throws Exception {
        cancellation.throwIfCancelled();
        try {
            if (DocumentPreviewRpcContracts.READ.equals(method)) {
                var input = json.decode(params, DocumentPreviewRpcContracts.ReadPayload.class);
                return json.encode(service.read(id, input.handleId(), input.offsetBytes(), input.maxBytes()));
            }
            var command = json.decode(params, WriteCommand.class);
            if (command.expectedRevision() != 0) {
                throw new IllegalArgumentException("预览连接控制 revision 必须为零");
            }
            return control(method, params, command);
        } catch (IOException failure) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, "PREVIEW_READ_FAILED: 内容不可用、读取期间发生变化或受控读取失败");
        }
    }

    private CanonicalPayload control(String method, CanonicalPayload params, WriteCommand command) throws Exception {
        String key = method + ":" + command.idempotencyKey();
        Receipt previous = receipts.get(key);
        if (previous != null) {
            if (!previous.digest().equals(params.sha256())) {
                throw new ProtocolException(ProtocolErrorCode.IDEMPOTENCY_CONFLICT, "预览幂等键已被其他参数使用");
            }
            return previous.response();
        }
        if (receipts.size() >= MAXIMUM_RECEIPTS) {
            throw new IllegalArgumentException("PREVIEW_SESSION_LIMIT: 请重新连接后继续预览");
        }
        CanonicalPayload result = execute(method, command.payload());
        receipts.put(key, new Receipt(params.sha256(), result));
        return result;
    }

    private CanonicalPayload execute(String method, CanonicalPayload payload) throws Exception {
        return switch (method) {
            case DocumentPreviewRpcContracts.RESOLVE -> {
                var input = json.decode(payload, DocumentPreviewRpcContracts.ResolvePayload.class);
                yield json.encode(service.resolve(id, input.reference(), cancellation));
            }
            case DocumentPreviewRpcContracts.RESOURCE -> {
                var input = json.decode(payload, DocumentPreviewRpcContracts.ResourcePayload.class);
                yield json.encode(service.resource(id, input.parentHandleId(), input.href(), cancellation));
            }
            case DocumentPreviewRpcContracts.RENEW -> {
                var input = json.decode(payload, DocumentPreviewRpcContracts.HandlePayload.class);
                yield json.encode(service.renew(id, input.handleId()));
            }
            case DocumentPreviewRpcContracts.CLOSE -> {
                var input = json.decode(payload, DocumentPreviewRpcContracts.HandlePayload.class);
                service.closeHandle(id, input.handleId());
                yield json.encode(new DocumentPreviewRpcContracts.CloseResult(input.handleId(), true));
            }
            default -> throw new IllegalArgumentException("未知文档预览方法");
        };
    }

    @Override
    public void close() throws IOException {
        cancellation.cancel("预览连接已关闭");
        service.closeSession(id);
    }

    private record Receipt(String digest, CanonicalPayload response) {}
}
