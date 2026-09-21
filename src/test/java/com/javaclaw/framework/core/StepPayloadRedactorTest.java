package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StepPayloadRedactorTest {
    @Test void masksNestedCredentialsAndProviderTokensWithoutChangingUsageOrCallerData() throws Exception {
        var original = new ObjectMapper().readTree("""
                {"arguments":{"password":"hidden-password","nested":[{"api_key":"hidden-key"}]},
                 "messages":[{"text":"credential sk-abcdefghijklmnop1234"}],
                 "usage":{"inputTokens":120,"outputTokens":8},"path":"src/main/Example.java"}
                """);
        var safe = StepPayloadRedactor.redact(original);
        assertFalse(safe.toString().contains("hidden-password"));
        assertFalse(safe.toString().contains("hidden-key"));
        assertFalse(safe.toString().contains("sk-abcdefghijklmnop1234"));
        assertEquals(120, safe.path("usage").path("inputTokens").asInt());
        assertEquals("src/main/Example.java", safe.path("path").asText());
        assertEquals("hidden-password", original.path("arguments").path("password").asText());
    }
}
