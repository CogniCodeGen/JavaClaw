package com.javaclaw.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigSchemaTest {

    @Test
    void initialDefaultsPreserveTheEstablishedFirstRunSchema() {
        Properties properties = new Properties();

        AgentConfigSchema.applyInitialDefaults(properties);

        assertEquals("OpenAI", properties.getProperty(AgentConfigSchema.KEY_PROVIDER_TYPE));
        assertEquals("qwen/qwen3.5-9b", properties.getProperty(AgentConfigSchema.KEY_MODEL_NAME));
        assertEquals("4", properties.getProperty(AgentConfigSchema.KEY_SCHEDULE_THREAD_POOL_SIZE));
        assertFalse(properties.containsKey(AgentConfigSchema.KEY_CONFIRMATION_TIMEOUT_DEFAULT));
    }

    @Test
    void resetOnlyReplacesUserFacingDefaultsAndKeepsIndependentSettings() {
        Properties properties = new Properties();
        properties.setProperty(AgentConfigSchema.KEY_MODEL_NAME, "custom-model");
        properties.setProperty("memory.graph.max.nodes", "42");

        AgentConfigSchema.resetUserSettings(properties);

        assertEquals("qwen/qwen3.5-9b", properties.getProperty(AgentConfigSchema.KEY_MODEL_NAME));
        assertEquals("60", properties.getProperty(AgentConfigSchema.KEY_CONFIRMATION_TIMEOUT_DEFAULT));
        assertEquals("42", properties.getProperty("memory.graph.max.nodes"));
    }

    @Test
    void typedSettingsFallBackWhenPersistedNumbersAreMalformed() {
        Properties properties = new Properties();
        properties.setProperty("integer", "invalid");
        properties.setProperty("long", "invalid");
        properties.setProperty("double", "invalid");

        assertEquals(7, AgentConfigSchema.integer(properties, "integer", 7));
        assertEquals(11L, AgentConfigSchema.longValue(properties, "long", 11L));
        assertEquals(0.75, AgentConfigSchema.decimal(properties, "double", 0.75));
    }

    @Test
    void memorySettingsShareTheSameMutableSnapshot() {
        Properties properties = new Properties();
        AgentMemorySettings memory = new AgentMemorySettings(properties);

        memory.maxToken(4096);
        memory.tokenRatio(0.5);

        assertEquals(4096L, memory.maxToken());
        assertEquals(0.5, memory.tokenRatio());
        assertTrue(memory.graphEntitiesEnabled());
    }
}
