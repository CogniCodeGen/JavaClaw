package com.javaclaw.desktop.settings;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpPromptDescriptor;
import com.javaclaw.api.McpResourceDescriptor;
import com.javaclaw.api.McpTransport;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSettingsPageTest {
    @Test
    void https端点从编辑到外部数据和OAuth形成可操作闭环() {
        FxTestSupport.run(() -> {
            TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
            McpSettingsPage page = page(gateway);
            createHttps(page);

            assertEquals(
                    McpTransport.STREAMABLE_HTTPS,
                    endpoint(gateway, "docs-mcp").spec().transport());
            button(page, "启用").fire();
            assertEquals(McpEndpointState.ENABLED, endpoint(gateway, "docs-mcp").state());
            button(page, "健康检查").fire();
            button(page, "刷新 Catalog").fire();
            assertTrue(texts(page).stream().anyMatch(value -> value.contains("HEALTHY")));

            assertExternalData(page);
            configureApiKey(page);
            configureOAuth(page, gateway);
            assertFalse(page.dirty());
        });
    }

    @Test
    void 写入失败保留草稿且签名Bundle端点保持只读() {
        FxTestSupport.run(() -> {
            TestMcpSettingsGateway failing = new TestMcpSettingsGateway();
            failing.failEndpointWrite();
            McpSettingsPage failedPage = page(failing);
            fillNewEndpoint(failedPage);
            button(failedPage, "保存").fire();
            assertTrue(failedPage.dirty());
            assertTrue(texts(failedPage).stream().anyMatch(value -> value.contains("模拟 Endpoint 写入失败")));
            failedPage.discardDraft();
            assertFalse(failedPage.dirty());

            TestMcpSettingsGateway signed = new TestMcpSettingsGateway();
            McpEndpoint endpoint = signed.addSignedBundleEndpoint();
            McpSettingsPage signedPage = page(signed);
            selectEndpoint(signedPage, endpoint.id());

            assertTrue(texts(signedPage).stream().anyMatch(value -> value.contains("trusted.bundle（已验证签名，只读）")));
            assertTrue(field(signedPage, "展示名称").isDisabled());
            assertTrue(button(signedPage, "保存").isDisabled());
            assertFalse(button(signedPage, "健康检查").isDisabled());
            assertTrue(button(signedPage, "读取 Resource").isDisabled());
        });
    }

    private static McpSettingsPage page(TestMcpSettingsGateway gateway) {
        McpSettingsPage page = new McpSettingsPage(gateway);
        new Scene(page, 1_040, 720);
        page.activate();
        page.applyCss();
        return page;
    }

    private static void createHttps(McpSettingsPage page) {
        fillNewEndpoint(page);
        button(page, "保存").fire();
        assertFalse(page.dirty());
    }

    private static void fillNewEndpoint(McpSettingsPage page) {
        button(page, "新建 HTTPS Endpoint").fire();
        field(page, "MCP Endpoint 标识").setText("docs-mcp");
        field(page, "展示名称").setText("Docs MCP");
        field(page, "https://example.com/mcp").setText("https://mcp.example.test/rpc");
        combo(page, "MCP 认证方式").setValue(McpAuthType.NONE);
        assertTrue(page.dirty());
    }

    private static void assertExternalData(McpSettingsPage page) {
        button(page, "读取 Resource").fire();
        ListView<McpResourceDescriptor> resources = typedList(page, McpResourceDescriptor.class);
        resources.getSelectionModel().selectFirst();
        button(page, "读取所选内容").fire();
        assertTrue(textArea(page, "MCP 外部数据预览").getText().contains("hello"));

        button(page, "读取 Prompt").fire();
        ListView<McpPromptDescriptor> prompts = typedList(page, McpPromptDescriptor.class);
        prompts.getSelectionModel().selectFirst();
        textArea(page, "MCP Prompt 参数").setText("topic=v5");
        button(page, "展开所选 Prompt").fire();
        assertTrue(textArea(page, "MCP 外部数据预览").getText().contains("review"));
        textArea(page, "MCP Prompt 参数").setText("invalid");
        button(page, "展开所选 Prompt").fire();
        assertTrue(texts(page).stream().anyMatch(value -> value.contains("name=value")));
    }

    private static void configureApiKey(McpSettingsPage page) {
        combo(page, "MCP 认证方式").setValue(McpAuthType.API_KEY);
        field(page, "X-Api-Key").setText("X-Docs-Key");
        password(page, "MCP Secret").setText("sealed-only");
        button(page, "保存").fire();
        assertTrue(texts(page).contains("已配置（不可读取）"));
    }

    private static void configureOAuth(McpSettingsPage page, TestMcpSettingsGateway gateway) {
        combo(page, "MCP 认证方式").setValue(McpAuthType.OAUTH_2_1_PKCE);
        button(page, "保存").fire();
        button(page, "启动 OAuth").fire();
        assertTrue(texts(page).contains("PENDING"));
        button(page, "取消授权").fire();
        assertTrue(texts(page).contains("CANCELLED"));
        button(page, "重新授权").fire();
        gateway.authorizeOAuth();
        button(page, "刷新授权状态").fire();
        assertTrue(texts(page).contains("AUTHORIZED"));
    }

    private static McpEndpoint endpoint(TestMcpSettingsGateway gateway, String id) {
        return gateway.mcpEndpoint(id).toCompletableFuture().join();
    }

    private static void selectEndpoint(McpSettingsPage page, String id) {
        ListView<McpEndpoint> endpoints = typedList(page, McpEndpoint.class);
        McpEndpoint selected = endpoints.getItems().stream()
                .filter(value -> value.id().equals(id))
                .findFirst()
                .orElseThrow();
        endpoints.getSelectionModel().select(selected);
    }

    private static Button button(Parent root, String text) {
        return nodes(root, Button.class).stream()
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少按钮: " + text));
    }

    private static TextField field(Parent root, String prompt) {
        return nodes(root, TextField.class).stream()
                .filter(value -> prompt.equals(value.getPromptText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少字段: " + prompt));
    }

    private static PasswordField password(Parent root, String accessibleText) {
        return nodes(root, PasswordField.class).stream()
                .filter(value -> accessibleText.equals(value.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> ComboBox<T> combo(Parent root, String accessibleText) {
        return (ComboBox<T>) nodes(root, ComboBox.class).stream()
                .filter(value -> accessibleText.equals(value.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static TextArea textArea(Parent root, String accessibleText) {
        return nodes(root, TextArea.class).stream()
                .filter(value -> accessibleText.equals(value.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> ListView<T> typedList(Parent root, Class<T> type) {
        return (ListView<T>) nodes(root, ListView.class).stream()
                .filter(value -> !value.getItems().isEmpty())
                .filter(value -> type.isInstance(value.getItems().getFirst()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> texts(Parent root) {
        return nodes(root, Label.class).stream().map(Label::getText).toList();
    }

    private static <T extends Node> List<T> nodes(Parent root, Class<T> type) {
        java.util.ArrayList<T> result = new java.util.ArrayList<>();
        Queue<Node> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node current = pending.remove();
            if (type.isInstance(current)) {
                result.add(type.cast(current));
            }
            if (current instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return List.copyOf(result);
    }
}
