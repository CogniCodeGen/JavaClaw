package com.javaclaw.mcp;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.SecretRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpManageToolsTest {

    private boolean previousConfirmationState;
    private UserInteractionPort previousPort;

    @BeforeEach
    void disableConfirmation() {
        previousConfirmationState = ToolConfirmationManager.isEnabled();
        previousPort = ToolConfirmationManager.getPort();
        ToolConfirmationManager.setEnabled(false);
    }

    @AfterEach
    void restoreConfirmation() {
        ToolConfirmationManager.setEnabled(previousConfirmationState);
        ToolConfirmationManager.setPort(previousPort);
    }

    @Test
    void addRejectsHeaderSecretFromConversationJson() {
        FakeStore store = new FakeStore();
        FakeRuntime runtime = new FakeRuntime();
        McpManageTools tools = new McpManageTools(ToolCallOrigin.INTERACTIVE, store, runtime);

        String response = tools.addServer("remote", """
                {"url":"https://93.184.216.34/api","headers":{"Authorization":"Bearer secret-token"}}
                """, true);

        assertTrue(response.contains("[失败]"), response);
        assertFalse(response.contains("secret-token"), response);
        assertNull(store.get("remote"));
        assertTrue(runtime.started.isEmpty());
    }

    @Test
    void secureHeaderInputPersistsWithoutEnteringToolArgumentsOrResponse() {
        FakeStore store = new FakeStore();
        FakeRuntime runtime = new FakeRuntime();
        store.save(new McpServerConfig(
                "remote", "https://93.184.216.34/api", Map.of(), false));
        ToolConfirmationManager.setPort(new UserInteractionPort() {
            @Override public boolean confirm(ConfirmRequest request) { return true; }
            @Override public char[] requestSecret(SecretRequest request) {
                return "Bearer local-only-token".toCharArray();
            }
            @Override public void notify(ToastRequest request) { }
        });
        McpManageTools tools = new McpManageTools(ToolCallOrigin.INTERACTIVE, store, runtime);

        String response = tools.setHeaderSecure("remote", "Authorization");

        assertTrue(response.contains("[成功]"), response);
        assertFalse(response.contains("local-only-token"), response);
        assertEquals("Bearer local-only-token",
                store.get("remote").getHeaders().get("Authorization"));
        assertTrue(runtime.started.isEmpty());
    }

    @Test
    void failedStartupDoesNotUndoConfirmedConfigWrite() {
        FakeStore store = new FakeStore();
        FakeRuntime runtime = new FakeRuntime();
        runtime.startSucceeds = false;
        McpManageTools tools = new McpManageTools(ToolCallOrigin.INTERACTIVE, store, runtime);

        String response = tools.addServer("remote-failing",
                "{\"url\":\"https://93.184.216.34/failing\"}", true);

        assertTrue(response.contains("[成功]"), response);
        assertTrue(response.contains("已确认写入数据库"), response);
        assertNotNull(store.get("remote-failing"));
    }

    @Test
    void strictIsolationRejectsLocalStdioServer() {
        FakeStore store = new FakeStore();
        McpManageTools tools = new McpManageTools(
                ToolCallOrigin.INTERACTIVE, store, new FakeRuntime());

        String response = tools.addServer(
                "local", "{\"command\":\"npx\",\"args\":[\"-y\",\"pkg\"]}", true);

        assertTrue(response.contains("[失败]") && response.contains("仅允许 HTTP MCP"), response);
        assertNull(store.get("local"));
    }

    @Test
    void strictIsolationRejectsLoopbackHttpServer() {
        FakeStore store = new FakeStore();
        McpManageTools tools = new McpManageTools(
                ToolCallOrigin.INTERACTIVE, store, new FakeRuntime());

        String response = tools.addServer(
                "local-http", "{\"url\":\"http://127.0.0.1:8080/mcp\"}", true);

        assertTrue(response.contains("[失败]") && response.contains("回环 MCP"), response);
        assertNull(store.get("local-http"));
    }

    @Test
    void existingNameRequiresExplicitUpdateTool() {
        FakeStore store = new FakeStore();
        store.save(new McpServerConfig("existing", "node", List.of("server.js"), Map.of(), false));
        McpManageTools tools = new McpManageTools(
                ToolCallOrigin.INTERACTIVE, store, new FakeRuntime());

        String response = tools.addServer("existing", "{\"command\":\"other\"}", false);

        assertTrue(response.contains("[失败]"), response);
        assertEquals("node", store.get("existing").getCommand());
    }

    @Test
    void listNeverReturnsHttpQueryOrUserInfo() {
        FakeStore store = new FakeStore();
        store.save(new McpServerConfig("remote",
                "https://user:secret@93.184.216.34/mcp?token=opaque#part",
                Map.of("Authorization", "Bearer hidden"), false));
        McpManageTools tools = new McpManageTools(
                ToolCallOrigin.INTERACTIVE, store, new FakeRuntime());

        String response = tools.listServers();

        assertTrue(response.contains("https://93.184.216.34/mcp"), response);
        assertFalse(response.contains("secret"), response);
        assertFalse(response.contains("opaque"), response);
        assertFalse(response.contains("user:"), response);
    }

    private static final class FakeStore implements McpManageTools.ServerStore {
        private final Map<String, McpServerConfig> values = new LinkedHashMap<>();

        @Override public List<McpServerConfig> all() { return new ArrayList<>(values.values()); }
        @Override public McpServerConfig get(String name) { return values.get(name); }
        @Override public McpServerConfig save(McpServerConfig config) {
            values.put(config.getName(), config);
            return config;
        }
        @Override public boolean delete(String name) { return values.remove(name) != null; }
    }

    private static final class FakeRuntime implements McpManageTools.ServerRuntime {
        private final List<String> started = new ArrayList<>();
        private boolean startSucceeds = true;

        @Override public boolean start(McpServerConfig config) {
            started.add(config.getName());
            return startSucceeds;
        }
        @Override public boolean restart(McpServerConfig config) { return start(config); }
        @Override public void stop(String name) { }
        @Override public McpClientManager.ServerStatus status(String name) {
            return new McpClientManager.ServerStatus(
                    startSucceeds ? McpClient.ServerState.RUNNING : McpClient.ServerState.FAILED,
                    startSucceeds ? 3 : 0,
                    startSucceeds ? null : "connection refused",
                    0L);
        }
    }
}
