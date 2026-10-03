package com.javaclaw.config;

import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GeneralSettings;
import com.javaclaw.desktop.agent.ConfiguredDesktopAccess;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopConsentPort;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSystemPermissionService;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.infrastructure.settings.AgentConfigBehaviorSettingsAdapter;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigBehaviorTest {

    @TempDir
    Path temporaryDirectory;

    private AnnotationConfigApplicationContext context;
    private AgentConfig config;
    private SqlPropertyStore store;

    @BeforeEach
    void createConfiguration() {
        context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data")));
        config = context.getBean(AgentConfig.class);
        store = context.getBean(SqlPropertyStore.class);
        config.save();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void tieredModelsFallBackAsAUnitAndEncryptIndependentKeys() {
        config.setProviderType("OpenAI");
        config.setBaseUrl("https://high.example/v1");
        config.setModelName("high-model");
        config.setApiKey("high-secret");
        config.setThinkingEnabled(true);

        config.setNormalModelName(null);
        assertFalse(config.isNormalTierConfigured());
        assertEquals("OpenAI", config.getNormalProviderType());
        assertEquals("https://high.example/v1", config.getNormalBaseUrl());
        assertEquals("high-model", config.getNormalModelName());
        assertEquals("high-secret", config.getNormalApiKey());
        assertTrue(config.isNormalThinkingEnabled());

        config.setNormalModelName(" normal-model ");
        config.setNormalProviderType(null);
        config.setNormalBaseUrl(null);
        config.setNormalApiKey(null);
        assertTrue(config.isNormalTierConfigured());
        assertEquals("OpenAI", config.getNormalProviderType());
        assertEquals("https://high.example/v1", config.getNormalBaseUrl());
        assertEquals("normal-model", config.getNormalModelName());
        assertEquals("high-secret", config.getNormalApiKey());
        config.setNormalProviderType("Anthropic");
        config.setNormalBaseUrl("https://normal.example");
        config.setNormalApiKey("normal-secret");
        config.setNormalThinkingEnabled(false);
        assertEquals("Anthropic", config.getNormalProviderType());
        assertEquals("https://normal.example", config.getNormalBaseUrl());
        assertEquals("normal-secret", config.getNormalApiKey());
        assertFalse(config.isNormalThinkingEnabled());
        config.setNormalApiKey("");
        assertEquals("high-secret", config.getNormalApiKey());

        config.setLightModelName(null);
        assertFalse(config.isLightTierConfigured());
        assertEquals("OpenAI", config.getLightProviderType());
        assertEquals("https://high.example/v1", config.getLightBaseUrl());
        assertEquals("high-model", config.getLightModelName());
        assertEquals("high-secret", config.getLightApiKey());
        assertFalse(config.isLightThinkingEnabled());

        config.setLightModelName(" light-model ");
        config.setLightProviderType(null);
        config.setLightBaseUrl(null);
        config.setLightApiKey(null);
        assertTrue(config.isLightTierConfigured());
        assertEquals("OpenAI", config.getLightProviderType());
        assertEquals("https://high.example/v1", config.getLightBaseUrl());
        config.setLightProviderType("Gemini");
        config.setLightBaseUrl("https://light.example");
        config.setLightApiKey("light-secret");
        config.setLightThinkingEnabled(true);
        assertEquals("Gemini", config.getLightProviderType());
        assertEquals("https://light.example", config.getLightBaseUrl());
        assertEquals("light-secret", config.getLightApiKey());
        assertTrue(config.isLightThinkingEnabled());
        config.setLightApiKey("");
        assertEquals("high-secret", config.getLightApiKey());

        Properties snapshot = config.snapshotProperties();
        assertNotEquals("normal-secret", snapshot.getProperty(AgentConfigSchema.KEY_NORMAL_API_KEY));
        snapshot.setProperty(AgentConfigSchema.KEY_MODEL_NAME, "external-mutation");
        assertEquals("high-model", config.getModelName());
    }

    @Test
    void malformedAndNonPositiveSettingsUseDocumentedDefaults() {
        raw(AgentConfigSchema.KEY_LOOP_SIMILARITY_THRESHOLD, "invalid");
        raw(AgentConfigSchema.KEY_EVALUATOR_PASS_THRESHOLD, "invalid");
        raw(AgentConfigSchema.KEY_GEPA_EVAL_THRESHOLD, "invalid");
        raw(AgentConfigSchema.KEY_SKILL_EVOLUTION_SUCCESS_THRESHOLD, "invalid");
        raw(AgentConfigSchema.KEY_MODEL_REQUEST_TIMEOUT, "0");
        raw(AgentConfigSchema.KEY_SUBTASK_TOOL_ERROR_MAX, "-1");
        raw(AgentConfigSchema.KEY_SDD_EXEC_TIMEOUT, "0");
        raw(AgentConfigSchema.KEY_SDD_STRUCTURED_TIMEOUT, "-2");
        raw(AgentConfigSchema.KEY_SDD_EXEC_MAX_ITERS, "0");
        config.reload();

        assertEquals(AgentConfigSchema.DEFAULT_LOOP_SIMILARITY_THRESHOLD,
                config.getLoopSimilarityThreshold());
        assertEquals(AgentConfigSchema.DEFAULT_EVALUATOR_PASS_THRESHOLD,
                config.getEvaluatorPassThreshold());
        assertEquals(AgentConfigSchema.DEFAULT_GEPA_EVAL_THRESHOLD,
                config.getGepaEvalThreshold());
        assertEquals(0.6, config.getSkillEvolutionSuccessThreshold());
        assertEquals(AgentConfigSchema.DEFAULT_MODEL_REQUEST_TIMEOUT,
                config.getModelRequestTimeoutSeconds());
        assertEquals(AgentConfigSchema.DEFAULT_SUBTASK_TOOL_ERROR_MAX,
                config.getSubtaskToolErrorMax());
        assertEquals(AgentConfigSchema.DEFAULT_SDD_EXEC_TIMEOUT,
                config.getSddExecTimeoutSeconds());
        assertEquals(AgentConfigSchema.DEFAULT_SDD_STRUCTURED_TIMEOUT,
                config.getSddStructuredTimeoutSeconds());
        assertEquals(AgentConfigSchema.DEFAULT_SDD_EXEC_MAX_ITERS,
                config.getSddExecMaxIters());

        config.setLoopSimilarityThreshold(0.42);
        config.setEvaluatorPassThreshold(4.25);
        config.setGepaEvalThreshold(3.75);
        config.setSkillEvolutionSuccessThreshold(0.8);
        config.setModelRequestTimeoutSeconds(90);
        config.setSubtaskToolErrorMax(3);
        assertEquals(0.42, config.getLoopSimilarityThreshold());
        assertEquals(4.25, config.getEvaluatorPassThreshold());
        assertEquals(3.75, config.getGepaEvalThreshold());
        assertEquals(0.8, config.getSkillEvolutionSuccessThreshold());
        assertEquals(90, config.getModelRequestTimeoutSeconds());
        assertEquals(3, config.getSubtaskToolErrorMax());
    }

    @Test
    void desktopAccessSwitchGrantsAllSessionPurposesOnlyWhileOsPermissionsAreReady() {
        java.util.concurrent.atomic.AtomicBoolean permitted = new java.util.concurrent.atomic.AtomicBoolean();
        DesktopSystemPermissionService osPermissions = new DesktopSystemPermissionService() {
            @Override public DesktopAvailability status() {
                return new DesktopAvailability(permitted.get(), "test", 0,
                        permitted.get() ? "ready" : "permission missing");
            }
            @Override public DesktopAvailability requestPermissions() { return status(); }
        };
        ConfiguredDesktopAccess access = new ConfiguredDesktopAccess(config, osPermissions);
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "session", "chat", "chat-1");
        DesktopTarget target = new DesktopTarget("test", "target", 1, "App", "Window",
                0, 0, 1, 1, DesktopTarget.VISIBLE);

        assertFalse(access.enabled());
        assertThrows(SecurityException.class,
                () -> access.request(owner, target, DesktopConsentPort.Purpose.OBSERVE));
        config.setComputerAppAccessEnabled(true);
        assertTrue(access.enabled());
        assertFalse(access.accessStatus().available());
        assertThrows(IllegalStateException.class,
                () -> access.request(owner, target, DesktopConsentPort.Purpose.CONTROL));
        permitted.set(true);
        assertTrue(access.accessStatus().available());
        for (DesktopConsentPort.Purpose purpose : DesktopConsentPort.Purpose.values()) {
            assertTrue(access.request(owner, target, purpose));
        }
        permitted.set(false);
        assertFalse(access.accessStatus().available());
        config.setComputerAppAccessEnabled(false);
        assertFalse(access.enabled());
    }

    @Test
    void modesPersistenceAndResetKeepAStablePublicConfigurationSurface() {
        config.setHttpVersion("HTTP_2");
        assertTrue(config.isHttp2());
        config.setHttpVersion("HTTP_1_1");
        assertFalse(config.isHttp2());

        for (String mode : new String[]{"off", "AUTO", "unknown", "  suggest  "}) {
            config.setSkillEvolutionMode(mode);
            String expected = mode.strip().equalsIgnoreCase("off") ? "off"
                    : mode.strip().equalsIgnoreCase("auto") ? "auto" : "suggest";
            assertEquals(expected, config.getSkillEvolutionMode());
        }
        config.setToolReviewMode(null);
        assertEquals(ToolReviewMode.SMART, config.getToolReviewMode());
        config.setToolReviewMode(ToolReviewMode.MANUAL);
        assertEquals(ToolReviewMode.MANUAL, config.getToolReviewMode());

        config.setModelName("persisted-model");
        config.saveToolReviewModeAsync(null);
        config.setModelName("memory-only");
        config.reload();
        assertEquals("persisted-model", config.getModelName());

        AtomicInteger executions = new AtomicInteger();
        config.setToolReviewMode(ToolReviewMode.AUTO);
        config.saveToolReviewModeAsync(command -> {
            executions.incrementAndGet();
            command.run();
        });
        assertEquals(1, executions.get());
        config.setToolReviewMode(ToolReviewMode.MANUAL);
        config.reload();
        assertEquals(ToolReviewMode.AUTO, config.getToolReviewMode());

        assertTrue(config.getConfigFilePath().contains("javaclaw"));
        config.setModelName("custom-before-reset");
        config.resetToDefaults();
        assertEquals(AgentConfigSchema.DEFAULT_MODEL_NAME, config.getModelName());
    }

    @Test
    void computerAppAccessIsOffByDefaultAndPersistsAcrossReload() {
        AgentConfigBehaviorSettingsAdapter settings =
                new AgentConfigBehaviorSettingsAdapter(config);
        assertFalse(config.isComputerAppAccessEnabled());
        assertFalse(settings.load().general().computerAppAccessEnabled());

        settings.saveGeneral(new GeneralSettings(true, true));
        assertTrue(config.isComputerAppAccessEnabled());
        config.reload();
        assertTrue(settings.load().general().computerAppAccessEnabled());

        settings.saveGeneral(new GeneralSettings(true, false));
        assertFalse(config.isComputerAppAccessEnabled());
        config.reload();
        assertFalse(settings.load().general().computerAppAccessEnabled());

        settings.saveGeneral(new GeneralSettings(true, true));
        config.resetToDefaults();
        assertFalse(config.isComputerAppAccessEnabled());
    }

    @Test
    void failedSettingsSaveCannotGrantDesktopAccessInMemory() {
        AgentConfigBehaviorSettingsAdapter settings =
                new AgentConfigBehaviorSettingsAdapter(config);
        context.getBean(JdbcTemplate.class).execute("DROP TABLE app_properties");

        assertThrows(IllegalStateException.class,
                () -> settings.saveGeneral(new GeneralSettings(false, true)));
        assertFalse(config.isComputerAppAccessEnabled());
        assertTrue(config.isTrayMinimizeOnClose());
    }

    private void raw(String key, String value) {
        assertTrue(store.saveProperty(
                AgentConfigSchema.CONFIG_NAMESPACE, key, value, store.currentWorkspaceId()));
    }
}
