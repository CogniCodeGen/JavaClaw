package com.javaclaw.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigObsoletePlanPropertiesTest {

    @Test
    void 加载时删除已由Profile取代的旧规划限制() {
        Properties properties = new Properties();
        properties.setProperty("plan.mode.max.rounds", "1");
        properties.setProperty("plan.mode.max.experts", "2");
        properties.setProperty("api.model.name", "model");

        assertTrue(AgentConfig.removeObsoletePlanModeProperties(properties));
        assertEquals("model", properties.getProperty("api.model.name"));
        assertFalse(properties.containsKey("plan.mode.max.rounds"));
        assertFalse(properties.containsKey("plan.mode.max.experts"));
        assertFalse(AgentConfig.removeObsoletePlanModeProperties(properties));
    }
}
