package com.javaclaw.notification;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
import com.javaclaw.framework.spi.ToolEffectCapture;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationToolsBehaviorTest {

    private AnnotationConfigApplicationContext context;
    private NotificationConfig notificationConfig;
    private EmailConfig emailConfig;
    private boolean previousConfirmationState;
    private NotificationTools tools;

    @BeforeAll
    void createConfiguration(@TempDir Path temporaryDirectory) {
        context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data")));
        notificationConfig = context.getBean(NotificationConfig.class);
        emailConfig = context.getBean(EmailConfig.class);
        previousConfirmationState = ToolConfirmationManager.isEnabled();
        ToolConfirmationManager.setEnabled(false);
    }

    @BeforeEach
    void resetChannels() {
        notificationConfig.setDingtalkEnabled(false);
        notificationConfig.setDingtalkWebhook("");
        notificationConfig.setDingtalkSecret("");
        notificationConfig.setWechatEnabled(false);
        notificationConfig.setWechatWebhook("");
        notificationConfig.setFeishuEnabled(false);
        notificationConfig.setFeishuWebhook("");
        notificationConfig.setFeishuSecret("");
        notificationConfig.setEmailNotifyEnabled(false);
        notificationConfig.setEmailNotifyTo("");
        notificationConfig.setCustomEnabled(false);
        notificationConfig.setCustomWebhook("");
        tools = new NotificationTools(null, notificationConfig, emailConfig);
    }

    @AfterAll
    void closeConfiguration() {
        ToolConfirmationManager.setEnabled(previousConfirmationState);
        context.close();
    }

    @Test
    void providerAcknowledgementRequiresNumericProtocolCode() {
        assertTrue(NotificationTools.webhookAccepted("dingtalk", "{\"errcode\":0,\"errmsg\":\"ok\"}"));
        assertTrue(NotificationTools.webhookAccepted("feishu", "{\"code\":0}"));
        assertFalse(NotificationTools.webhookAccepted("dingtalk", "{\"errcode\":1,\"errmsg\":\"发送成功\"}"));
        assertFalse(NotificationTools.webhookAccepted("wechat", "{\"errmsg\":\"ok\"}"));
        assertFalse(NotificationTools.webhookAccepted("dingtalk", "{\"code\":0}"));
        assertFalse(NotificationTools.webhookAccepted("feishu", "{\"errcode\":0}"));
        assertFalse(NotificationTools.webhookAccepted("feishu", "{\"message\":\"\\\"code\\\":0\"}"));
    }

    @Test
    void listsBothDisabledAndConfiguredChannelStates() {
        String disabled = tools.listChannels();
        assertTrue(disabled.contains("未启用") && disabled.contains("未配置"), disabled);

        notificationConfig.setDingtalkEnabled(true);
        notificationConfig.setDingtalkWebhook("https://notify.invalid/dingtalk");
        notificationConfig.setWechatEnabled(true);
        notificationConfig.setWechatWebhook("https://notify.invalid/wechat");
        notificationConfig.setFeishuEnabled(true);
        notificationConfig.setFeishuWebhook("https://notify.invalid/feishu");
        notificationConfig.setEmailNotifyEnabled(true);
        notificationConfig.setEmailNotifyTo("owner@example.invalid");
        notificationConfig.setCustomEnabled(true);
        notificationConfig.setCustomWebhook("https://notify.invalid/custom");

        String configured = tools.listChannels();
        assertTrue(configured.contains("已启用")
                && configured.contains("owner@example.invalid")
                && configured.contains("（已配置）"), configured);
    }

    @Test
    void routesEmptyUnknownAndDisabledChannelsWithoutExternalCalls() {
        assertEquals(NotificationTools.DeliveryStatus.SKIPPED,
                tools.sendByChannelResult("none", "title", "message").status());
        assertEquals(NotificationTools.DeliveryStatus.FAILED,
                tools.sendByChannelResult("carrier-pigeon", "title", "message").status());
        assertAll(
                () -> assertTrue(tools.sendByChannel(null, "title", "message")
                        .contains("未选择")),
                () -> assertTrue(tools.sendByChannel(" ", "title", "message")
                        .contains("未选择")),
                () -> assertTrue(tools.sendByChannel("NONE", "title", "message")
                        .contains("未选择")),
                () -> assertTrue(tools.sendByChannel("all", "title", "message")
                        .contains("未启用任何")),
                () -> assertTrue(tools.sendByChannel("dingtalk", "title", "message")
                        .contains("钉钉通知未启用")),
                () -> assertTrue(tools.sendByChannel("wechat", "title", "message")
                        .contains("企业微信通知未启用")),
                () -> assertTrue(tools.sendByChannel("feishu", "title", "message")
                        .contains("飞书通知未启用")),
                () -> assertTrue(tools.sendByChannel("email", "title", "message")
                        .contains("邮件通知未启用")),
                () -> assertTrue(tools.sendByChannel("custom", "title", "message")
                        .contains("自定义 Webhook 未启用")),
                () -> assertTrue(tools.sendByChannel("carrier-pigeon", "title", "message")
                        .contains("未知通知渠道")));
    }

    @Test
    void publicSendersRejectMissingConfigurationBeforeTransport() {
        assertAll(
                () -> assertTrue(tools.sendNotification("message", null)
                        .contains("没有已启用")),
                () -> assertTrue(tools.sendDingtalk(null, "message", false)
                        .contains("未启用或未配置")),
                () -> assertTrue(tools.sendWechat("message", true)
                        .contains("未启用或未配置")),
                () -> assertTrue(tools.sendFeishu(null, "message")
                        .contains("未启用或未配置")),
                () -> assertTrue(tools.sendEmailNotify(null, "subject", "body")
                        .contains("邮件账号未配置")),
                () -> assertTrue(tools.sendEmailNotify("receiver@example.invalid", "subject", "body")
                        .contains("邮件账号未配置")),
                () -> assertTrue(tools.sendCustomWebhook("message")
                        .contains("未启用或未配置")));

        notificationConfig.setDingtalkEnabled(true);
        notificationConfig.setWechatEnabled(true);
        notificationConfig.setFeishuEnabled(true);
        notificationConfig.setCustomEnabled(true);
        assertAll(
                () -> assertTrue(tools.sendDingtalk("title", "message", true)
                        .contains("未启用或未配置")),
                () -> assertTrue(tools.sendWechat("message", false)
                        .contains("未启用或未配置")),
                () -> assertTrue(tools.sendFeishu("title", "message")
                        .contains("未启用或未配置")),
                () -> assertTrue(tools.sendCustomWebhook("message")
                        .contains("未启用或未配置")));
    }

    @Test
    void allWebhookTransportsPostToTheLocalFixture() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, requests, lastBody));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            notificationConfig.setDingtalkEnabled(true);
            notificationConfig.setDingtalkWebhook(baseUrl + "/dingtalk");
            notificationConfig.setWechatEnabled(true);
            notificationConfig.setWechatWebhook(baseUrl + "/wechat");
            notificationConfig.setFeishuEnabled(true);
            notificationConfig.setFeishuWebhook(baseUrl + "/feishu");
            notificationConfig.setCustomEnabled(true);
            notificationConfig.setCustomWebhook(baseUrl + "/custom");
            notificationConfig.setCustomContentType("application/json");
            notificationConfig.setCustomBodyTemplate("{\"message\":\"${message}\"}");

            String all = tools.sendNotification("E2E webhook body", "E2E webhook title");
            String dingtalk = tools.sendDingtalk("title", "message", true);
            String wechat = tools.sendWechat("message", true);
            String feishu = tools.sendFeishu("title", "message");
            String custom = tools.sendCustomWebhook("message");
            NotificationTools.DeliveryResult routed = tools.sendByChannelResult(
                    "custom", "title", "message");

            assertAll(
                    () -> assertTrue(all.contains("已接受: 4, 失败: 0"), all),
                    () -> assertTrue(dingtalk.contains("实际送达未验证"), dingtalk),
                    () -> assertTrue(wechat.contains("实际送达未验证"), wechat),
                    () -> assertTrue(feishu.contains("实际送达未验证"), feishu),
                    () -> assertTrue(custom.contains("实际送达未验证"), custom),
                    () -> assertEquals(NotificationTools.DeliveryStatus.ACCEPTED, routed.status()),
                    () -> assertTrue(lastBody.get().contains("message"), lastBody.get()));
            assertTrue(requests.get() == 9, "本地 Webhook 请求数不符: " + requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void partialBroadcastKeepsAcceptedChannelsAndSignalsUncertainty() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dingtalk", exchange -> {
            byte[] body = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/wechat", exchange -> {
            byte[] body = "{\"errcode\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            notificationConfig.setDingtalkEnabled(true);
            notificationConfig.setDingtalkWebhook(base + "/dingtalk");
            notificationConfig.setWechatEnabled(true);
            notificationConfig.setWechatWebhook(base + "/wechat");
            NotificationTools.DeliveryResult result = tools.sendByChannelResult(
                    "all", "title", "message");
            assertEquals(NotificationTools.DeliveryStatus.PARTIAL, result.status());
            assertEquals(NotificationTools.DeliveryStatus.ACCEPTED,
                    result.channels().get("dingtalk"));
            assertEquals(NotificationTools.DeliveryStatus.FAILED,
                    result.channels().get("wechat"));
            try (ToolEffectCapture.Scope captured = ToolEffectCapture.begin("notify_send")) {
                tools.sendNotification("message", "title");
                assertEquals(ToolEffectCapture.Signal.UNCERTAIN, captured.signal());
                assertEquals("PARTIAL", captured.data().path("status").asText());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void signedFeishuBroadcastIncludesProtocolSignature() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feishu", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            notificationConfig.setFeishuEnabled(true);
            notificationConfig.setFeishuWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/feishu");
            notificationConfig.setFeishuSecret("signed-fixture-secret");
            NotificationTools.DeliveryResult result = tools.sendByChannelResult(
                    "all", "title", "message");
            assertEquals(NotificationTools.DeliveryStatus.ACCEPTED, result.status());
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get());
            assertTrue(payload.path("timestamp").isTextual());
            assertTrue(payload.path("sign").isTextual()
                    && !payload.path("sign").asText().isBlank());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverErrorKeepsNonIdempotentDeliveryUncertain() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom", exchange -> {
            byte[] response = "temporary error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            notificationConfig.setCustomEnabled(true);
            notificationConfig.setCustomWebhook(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/custom");
            notificationConfig.setCustomBodyTemplate("{\"message\":\"${message}\"}");
            NotificationTools.DeliveryResult result = tools.sendByChannelResult(
                    "custom", "title", "message");
            assertEquals(NotificationTools.DeliveryStatus.UNCERTAIN, result.status());
            try (ToolEffectCapture.Scope captured = ToolEffectCapture.begin("notify_custom_webhook")) {
                tools.sendCustomWebhook("message");
                assertEquals(ToolEffectCapture.Signal.UNCERTAIN, captured.signal());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreadableSigningSecretsNeverReachWebhookTransport() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(
                exchange, requests, new AtomicReference<>()));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            notificationConfig.setDingtalkEnabled(true);
            notificationConfig.setDingtalkWebhook(baseUrl + "/dingtalk");
            notificationConfig.setDingtalkSecret("ENC(YmFk)");
            notificationConfig.setFeishuEnabled(true);
            notificationConfig.setFeishuWebhook(baseUrl + "/feishu");
            notificationConfig.setFeishuSecret("ENC(YmFk)");

            String dingtalk = tools.sendDingtalk("title", "message", false);
            String feishu = tools.sendFeishu("title", "message");
            String dingtalkRouted = tools.sendByChannel("dingtalk", "title", "message");
            String feishuRouted = tools.sendByChannel("feishu", "title", "message");
            String all = tools.sendNotification("message", "title");

            assertAll(
                    () -> assertTrue(dingtalk.contains("钉钉签名密钥无法解密"), dingtalk),
                    () -> assertTrue(feishu.contains("飞书签名密钥无法解密"), feishu),
                    () -> assertTrue(dingtalkRouted.contains("钉钉签名密钥无法解密"), dingtalkRouted),
                    () -> assertTrue(feishuRouted.contains("飞书签名密钥无法解密"), feishuRouted),
                    () -> assertTrue(all.contains("失败: 2"), all),
                    () -> assertTrue(requests.get() == 0,
                            "未解密密钥不应触发 Webhook 请求: " + requests.get()));
        } finally {
            server.stop(0);
        }
    }

    private static void respond(
            HttpExchange exchange,
            AtomicInteger requests,
            AtomicReference<String> lastBody) throws IOException {
        requests.incrementAndGet();
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String body = exchange.getRequestURI().getPath().contains("feishu")
                ? "{\"code\":0}" : "{\"errcode\":0,\"errmsg\":\"ok\"}";
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
