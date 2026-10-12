package com.javaclaw.site;

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

class SiteCredentialToolsTest {

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
    void conversationSaveNormalizesHostAndCreatesMetadataWithoutPassword() {
        FakeStore store = new FakeStore();
        SiteCredentialTools tools = new SiteCredentialTools(ToolCallOrigin.INTERACTIVE, store);

        String response = tools.saveCredential(
                "测试后台", "https://Login.Example.com/path", null,
                "https://login.example.com/sign-in", "tester@example.com", null, "测试环境");

        assertTrue(response.contains("[成功]"), response);
        SiteCredential saved = store.all().getFirst();
        assertEquals("login.example.com", saved.getHostPattern());
        assertNull(saved.getPassword());
        assertEquals("https://login.example.com/sign-in", saved.getLoginUrl());
    }

    @Test
    void rejectsPasswordAndCredentialLikeNotesFromConversationArguments() {
        FakeStore store = new FakeStore();
        SiteCredentialTools tools = new SiteCredentialTools(ToolCallOrigin.INTERACTIVE, store);

        String passwordResponse = tools.saveCredential(
                "测试后台", "example.com", null, null, "tester", "top-secret", null);
        String notesResponse = tools.saveCredential(
                "测试后台", "example.com", null, null, "tester", null,
                "密码：RealSecret-2026!");

        assertTrue(passwordResponse.contains("[失败]"), passwordResponse);
        assertTrue(passwordResponse.contains("安全"), passwordResponse);
        assertTrue(notesResponse.contains("[失败]"), notesResponse);
        assertTrue(store.all().isEmpty());
    }

    @Test
    void securePasswordInputPersistsWithoutEchoingSecret() {
        FakeStore store = new FakeStore();
        SiteCredential credential = new SiteCredential(
                "cred-1", "内部系统", "example.com", null, "tester", null, null);
        store.save(credential);
        ToolConfirmationManager.setPort(new UserInteractionPort() {
            @Override public boolean confirm(ConfirmRequest request) { return true; }
            @Override public char[] requestSecret(SecretRequest request) {
                return "local-only-secret".toCharArray();
            }
            @Override public void notify(ToastRequest request) { }
        });
        SiteCredentialTools tools = new SiteCredentialTools(ToolCallOrigin.INTERACTIVE, store);

        String response = tools.setPasswordSecure("cred-1");

        assertTrue(response.contains("[成功]"), response);
        assertFalse(response.contains("local-only-secret"), response);
        assertEquals("local-only-secret", store.get("cred-1").getPassword());
    }

    @Test
    void listMasksUsernameAndNeverReturnsPassword() {
        FakeStore store = new FakeStore();
        SiteCredential credential = new SiteCredential(
                "cred-1", "内部系统", "example.com", null,
                "someone@example.com", "do-not-leak", null);
        store.save(credential);
        SiteCredentialTools tools = new SiteCredentialTools(ToolCallOrigin.INTERACTIVE, store);

        String response = tools.listCredentials();

        assertTrue(response.contains("so***e@example.com"), response);
        assertFalse(response.contains("someone@example.com"), response);
        assertFalse(response.contains("do-not-leak"), response);
    }

    @Test
    void wildcardInputIsKeptButUrlPathIsDiscarded() {
        assertEquals("*.example.com", SiteCredentialTools.normalizeHostPattern("*.Example.com"));
        assertEquals("example.com", SiteCredentialTools.normalizeHostPattern("https://example.com/a/b"));
        assertThrows(IllegalArgumentException.class,
                () -> SiteCredentialTools.normalizeHostPattern("not a host"));
    }

    private static final class FakeStore implements SiteCredentialTools.Store {
        private final Map<String, SiteCredential> values = new LinkedHashMap<>();

        @Override public List<SiteCredential> all() { return new ArrayList<>(values.values()); }
        @Override public SiteCredential get(String id) { return values.get(id); }
        @Override public SiteCredential save(SiteCredential credential) {
            if (credential.getId() == null || credential.getId().isBlank()) {
                credential.setId("cred-" + (values.size() + 1));
            }
            values.put(credential.getId(), credential);
            return credential;
        }
        @Override public boolean delete(String id) { return values.remove(id) != null; }
    }
}
