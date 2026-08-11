package com.javaclaw.config;

import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialConfigIntegrationTest {

    @TempDir
    Path tempDirectory;

    @Test
    void rootManagedConfigurationsPersistSecretsOnlyThroughTheSharedCipher() {
        try (var context = ApplicationContexts.createRoot(
                new DataRoot(tempDirectory.resolve("credential-config")))) {
            AgentConfig agent = context.getBean(AgentConfig.class);
            EmailConfig email = context.getBean(EmailConfig.class);
            NotificationConfig notifications = context.getBean(NotificationConfig.class);
            CredentialCipher cipher = context.getBean(CredentialCipher.class);

            agent.setApiKey("agent-secret");
            agent.save();
            email.setPassword("mail-secret");
            email.save();
            notifications.setDingtalkSecret("dingtalk-secret");
            notifications.setFeishuSecret("feishu-secret");
            notifications.save();

            List<String> stored = context.getBean(JdbcTemplate.class).queryForList("""
                    SELECT prop_value
                    FROM app_properties
                    WHERE prop_key IN ('api.key', 'password', 'dingtalk.secret', 'feishu.secret')
                    ORDER BY namespace, prop_key
                    """, String.class);
            assertEquals(4, stored.size());
            assertTrue(stored.stream().allMatch(cipher::isEncrypted));
            assertFalse(stored.stream().anyMatch(value -> value.contains("secret")));

            agent.reload();
            email.reload();
            notifications.reload();
            assertEquals("agent-secret", agent.getApiKey());
            assertEquals("mail-secret", email.getPassword());
            assertEquals("dingtalk-secret", notifications.getDingtalkSecret());
            assertEquals("feishu-secret", notifications.getFeishuSecret());
        }
    }
}
