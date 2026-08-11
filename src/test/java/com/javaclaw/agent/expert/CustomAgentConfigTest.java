package com.javaclaw.agent.expert;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CustomAgentConfigTest {

    @Test
    void persistsChangesAndKeepsWorkspaceCachesIsolated() {
        JdbcTemplate jdbc = jdbc();
        CustomAgentConfig first = new CustomAgentConfig("workspace-a", jdbc);
        CustomAgentConfig second = new CustomAgentConfig("workspace-b", jdbc);

        CustomAgentConfig.CustomAgentDef created = first.create("测试智能体");
        created.name = "修改后的名称";
        created.toolName = "changed_agent";
        created.enabled = false;
        first.update(created);

        assertEquals("修改后的名称", first.get(created.id).name);
        assertFalse(first.get(created.id).enabled);
        assertNull(second.get(created.id));

        CustomAgentConfig reloaded = new CustomAgentConfig("workspace-a", jdbc);
        assertNotNull(reloaded.get(created.id));
        reloaded.delete(created.id);
        assertNull(reloaded.get(created.id));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM custom_agents WHERE workspace_id = ?",
                Integer.class, "workspace-a"));
    }

    @Test
    void returnedDefinitionsCannotMutateInternalCache() {
        CustomAgentConfig config = new CustomAgentConfig("workspace", jdbc());
        CustomAgentConfig.CustomAgentDef created = config.create("原名称");

        CustomAgentConfig.CustomAgentDef snapshot = config.get(created.id);
        snapshot.name = "未保存的名称";

        assertEquals("原名称", config.get(created.id).name);
    }

    private JdbcTemplate jdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agents-" + java.util.UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE custom_agents (
                    workspace_id VARCHAR(64) NOT NULL,
                    id VARCHAR(64) NOT NULL,
                    name VARCHAR(255),
                    tool_name VARCHAR(255),
                    description CLOB,
                    sys_prompt CLOB,
                    max_iters INT,
                    enabled BOOLEAN,
                    updated_at TIMESTAMP,
                    PRIMARY KEY(workspace_id, id)
                )
                """);
        return jdbc;
    }
}
