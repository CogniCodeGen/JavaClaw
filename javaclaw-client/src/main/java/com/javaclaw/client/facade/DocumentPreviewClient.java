package com.javaclaw.client.facade;

import java.util.Objects;

import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.DocumentPreviewRpcContracts;

/**
 * 文档预览 SDK；内容只读，版本句柄属于当前连接，断线后必须重新解析来源。
 *
 * <p>本类型不读取本地文件，也不在通知回调中同步调用 RPC；调用者须在后台线程执行并处理请求代次。
 */
public final class DocumentPreviewClient {
    private final RpcClientConnection connection;

    /** @param connection 已初始化连接，调用者管理连接生命周期 */
    public DocumentPreviewClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 监听当前连接的失效事件；回调只能快速入队，不能同步执行 RPC。
     *
     * @param listener 失效处理器
     * @return 移除监听器的资源，调用者在页面关闭或连接切换时释放
     */
    public AutoCloseable onInvalidated(java.util.function.Consumer<DocumentPreviewRpcContracts.Invalidated> listener) {
        Objects.requireNonNull(listener, "listener");
        var json = new com.javaclaw.protocol.CanonicalJson();
        return connection.observe(notification -> {
            if (DocumentPreviewRpcContracts.INVALIDATED.equals(notification.method())) {
                listener.accept(json.decode(notification.params(), DocumentPreviewRpcContracts.Invalidated.class));
            }
        });
    }

    /**
     * @param reference 权威来源引用
     * @param command 连接内幂等键，revision 为零
     * @return 不可变版本句柄
     */
    public DocumentPreview resolve(DocumentReference reference, CommandOptions command) {
        return connection.command(
                DocumentPreviewRpcContracts.RESOLVE,
                new DocumentPreviewRpcContracts.ResolvePayload(reference),
                control(command),
                DocumentPreview.class);
    }

    /**
     * @param handleId 当前连接句柄
     * @param offsetBytes 起始字节
     * @param maxBytes 至多256KiB
     * @return 版本中的连续块
     */
    public DocumentChunk readChunk(String handleId, long offsetBytes, int maxBytes) {
        return connection.query(
                DocumentPreviewRpcContracts.READ,
                new DocumentPreviewRpcContracts.ReadPayload(handleId, offsetBytes, maxBytes),
                DocumentChunk.class);
    }

    /**
     * @param parentHandleId 父版本
     * @param href 真实相对链接
     * @param command 连接幂等键
     * @return 同权限来源的资源版本
     */
    public DocumentPreview resolveResource(String parentHandleId, String href, CommandOptions command) {
        return connection.command(
                DocumentPreviewRpcContracts.RESOURCE,
                new DocumentPreviewRpcContracts.ResourcePayload(parentHandleId, href),
                control(command),
                DocumentPreview.class);
    }

    /**
     * @param handleId 当前可见版本
     * @param command 连接幂等键
     * @return 重验授权后的新租约元信息
     */
    public DocumentPreview renew(String handleId, CommandOptions command) {
        return connection.command(
                DocumentPreviewRpcContracts.RENEW,
                new DocumentPreviewRpcContracts.HandlePayload(handleId),
                control(command),
                DocumentPreview.class);
    }

    /**
     * @param handleId 要释放的句柄
     * @param command 连接幂等键
     * @return 幂等关闭回执
     */
    public DocumentPreviewRpcContracts.CloseResult close(String handleId, CommandOptions command) {
        return connection.command(
                DocumentPreviewRpcContracts.CLOSE,
                new DocumentPreviewRpcContracts.HandlePayload(handleId),
                control(command),
                DocumentPreviewRpcContracts.CloseResult.class);
    }

    private static CommandOptions control(CommandOptions command) {
        Objects.requireNonNull(command, "command");
        if (command.expectedRevision() != 0) {
            throw new IllegalArgumentException("连接控制 expectedRevision 必须为零");
        }
        return command;
    }
}
