package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderPreviewMessagesTest {
    @Test
    void 目录分类给出可操作提示并保留业务版本冲突() {
        for (String code : List.of(
                "AUTHENTICATION_FAILED",
                "INVALID_ADDRESS",
                "TIMEOUT",
                "NETWORK_ERROR",
                "CATALOG_UNSUPPORTED",
                "PREVIEW_FAILED")) {
            String message = ProviderPreviewMessages.describe(new CompletionException(new IllegalStateException(code)));
            assertFalse(message.contains(code));
            assertTrue(message.contains("请") || message.contains("可以"));
        }
        assertTrue(ProviderPreviewMessages.describe(new RemoteRpcException(
                        new JsonRpcError(ProtocolErrorCode.REVISION_CONFLICT, "private exception", Optional.empty())))
                .contains("重新读取"));
        assertEquals(
                "模型目录暂时无法读取，可以重试或手动添加模型。",
                ProviderPreviewMessages.describe(new IllegalStateException("private exception")));
    }
}
