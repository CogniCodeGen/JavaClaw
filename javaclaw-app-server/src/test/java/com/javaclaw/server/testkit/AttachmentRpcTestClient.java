package com.javaclaw.server.testkit;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.AttachmentUploadSession;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.rpc.AppServerSession;

/** 测试通过真实 Protocol v2 route 分块创建 Attachment 的小型客户端。 */
public final class AttachmentRpcTestClient {
    private AttachmentRpcTestClient() {}

    /**
     * 依次调用 begin、若干 chunk 和 complete，不绕过 RPC handler。
     *
     * @param session 已初始化测试会话
     * @param json 组合根 JSON codec
     * @param key 测试内唯一键前缀
     * @param scope 显式所有权范围
     * @param mediaType MIME 类型
     * @param content 原始字节
     * @return 提交后的元数据
     */
    public static AttachmentMetadata upload(
            AppServerSession session,
            CanonicalJson json,
            String key,
            AttachmentScope scope,
            String mediaType,
            byte[] content) {
        String digest = HexFormat.of().formatHex(sha256(content));
        AttachmentUploadSession current = call(
                session,
                json,
                key + "-begin",
                "attachment/upload/begin",
                0,
                new AttachmentRpcContracts.BeginPayload(scope, mediaType, digest, content.length),
                AttachmentUploadSession.class);
        int offset = 0;
        while (offset < content.length) {
            int count = Math.min(AttachmentRpcContracts.MAX_ATTACHMENT_CHUNK_BYTES, content.length - offset);
            byte[] chunk = Arrays.copyOfRange(content, offset, offset + count);
            current = call(
                    session,
                    json,
                    key + "-chunk-" + current.nextChunkIndex(),
                    "attachment/upload/chunk",
                    current.revision(),
                    new AttachmentRpcContracts.ChunkPayload(scope, current.id(), current.nextChunkIndex(), chunk),
                    AttachmentUploadSession.class);
            offset += count;
        }
        return call(
                session,
                json,
                key + "-complete",
                "attachment/upload/complete",
                current.revision(),
                new AttachmentRpcContracts.CompletePayload(scope, current.id()),
                AttachmentMetadata.class);
    }

    private static <T> T call(
            AppServerSession session,
            CanonicalJson json,
            String key,
            String method,
            long expectedRevision,
            Object payload,
            Class<T> resultType) {
        WriteCommand command = new WriteCommand(key, expectedRevision, json.encode(payload));
        JsonRpcResponse response = session.handle(new JsonRpcRequest(new RpcId(key), method, json.encode(command)));
        if (response.error().isPresent()) {
            throw new AssertionError(
                    "Attachment RPC failed: " + response.error().orElseThrow());
        }
        return json.decode(response.result().orElseThrow(), resultType);
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
