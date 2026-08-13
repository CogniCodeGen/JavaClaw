package com.javaclaw.notification;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
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
        notificationConfig.setWechatEnabled(false);
        notificationConfig.setWechatWebhook("");
        notificationConfig.setFeishuEnabled(false);
        notificationConfig.setFeishuWebhook("");
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
            String routed = tools.sendByChannel("custom", "title", "message");

            assertAll(
                    () -> assertTrue(all.contains("成功: 4, 失败: 0"), all),
                    () -> assertTrue(dingtalk.contains("发送成功"), dingtalk),
                    () -> assertTrue(wechat.contains("发送成功"), wechat),
                    () -> assertTrue(feishu.contains("发送成功"), feishu),
                    () -> assertTrue(custom.contains("发送成功"), custom),
                    () -> assertTrue(routed.contains("发送成功"), routed),
                    () -> assertTrue(lastBody.get().contains("message"), lastBody.get()));
            assertTrue(requests.get() == 9, "本地 Webhook 请求数不符: " + requests.get());
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
