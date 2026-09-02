package com.javaclaw.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolDescriptor;

/** 为崩溃恢复证据计算不包含 Secret 原文的稳定调用摘要。 */
final class TurnInvocationDigests {
    private TurnInvocationDigests() {}

    static String model(ModelInvocation invocation, int invocationNumber) {
        MessageDigest digest = sha256();
        update(digest, "model");
        update(digest, Integer.toString(invocationNumber));
        update(digest, invocation.modelId());
        update(digest, invocation.systemInstruction());
        update(digest, Long.toString(invocation.maximumOutputTokens()));
        for (ModelMessage message : invocation.messages()) {
            update(digest, message.role().name());
            update(digest, message.text());
            update(digest, message.toolCallId().orElse(""));
            update(digest, message.toolName().orElse(""));
            for (ModelToolCall call : message.toolCalls()) {
                updateCall(digest, call);
            }
        }
        for (ToolDescriptor tool : invocation.tools()) {
            update(digest, tool.identity().producerId());
            update(digest, tool.identity().name());
            update(digest, Long.toString(tool.identity().revision()));
            update(digest, tool.inputSchema().sha256());
            update(digest, tool.outputSchema().sha256());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String tool(ToolCallRequest request, int toolIndex) {
        MessageDigest digest = sha256();
        update(digest, "tool");
        update(digest, Integer.toString(toolIndex));
        update(digest, request.turnId().toString());
        update(digest, request.callId());
        update(digest, request.tool().producerId());
        update(digest, request.tool().name());
        update(digest, Long.toString(request.tool().revision()));
        update(digest, request.arguments().sha256());
        update(digest, request.idempotencyKey());
        update(digest, Long.toString(request.expectedCatalogRevision()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateCall(MessageDigest digest, ModelToolCall call) {
        update(digest, call.callId());
        update(digest, call.tool().producerId());
        update(digest, call.tool().name());
        update(digest, Long.toString(call.tool().revision()));
        update(digest, call.arguments().sha256());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(new byte[] {
            (byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16), (byte) (bytes.length >>> 8), (byte) bytes.length
        });
        digest.update(bytes);
    }
}
