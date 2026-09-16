package com.javaclaw.server.turn;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.extension.BrowserOperationUnconfirmedException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolModelObservationsTest {
    @Test
    void 浏览器动作结果未确认仍保留可信续接且不生成提交回执或图片() {
        var result = ToolModelObservations.unconfirmed(
                        request(BuiltinExtensionIds.SITE, "browser_act"),
                        new BrowserOperationUnconfirmedException(),
                        true)
                .orElseThrow();
        assertFalse(result.result().success());
        assertTrue(result.result().receipt().isEmpty());
        assertTrue(result.images().isEmpty());
        assertTrue(result.yieldTurn());
        assertFalse(ToolModelObservations.unconfirmed(
                        request(BuiltinExtensionIds.SITE, "browser_act"),
                        new BrowserOperationUnconfirmedException(),
                        false)
                .orElseThrow()
                .yieldTurn());
    }

    @Test
    void 同名第三方工具和普通执行异常不能冒充浏览器未确认结果() {
        assertTrue(ToolModelObservations.unconfirmed(
                        request("untrusted.extension", "browser_act"), new BrowserOperationUnconfirmedException(), true)
                .isEmpty());
        assertTrue(ToolModelObservations.unconfirmed(
                        request(BuiltinExtensionIds.SITE, "unrelated"),
                        new BrowserOperationUnconfirmedException(),
                        true)
                .isEmpty());
        assertTrue(ToolModelObservations.unconfirmed(
                        request(BuiltinExtensionIds.SITE, "browser_act"),
                        new IllegalStateException("private data"),
                        true)
                .isEmpty());
    }

    private static ToolCallRequest request(String producer, String name) {
        return new ToolCallRequest(
                TurnId.parse(UUID.randomUUID().toString()),
                "call",
                new ToolIdentity(producer, name, 1),
                new CanonicalJson().parse("{}"),
                "action",
                1);
    }
}
