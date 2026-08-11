package com.javaclaw.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.skill.curation.SkillProposalQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillRuntimePersistenceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void usageIsRestoredAndIsolatedByWorkspace() {
        try (var root = ApplicationContexts.createRoot(new DataRoot(tempDirectory.resolve("usage-db")));
             TaskScope tasks = root.getBean(ManagedTaskExecutor.class)
                     .openScope("skill-usage-test", 4)) {
            SkillUsageTracker first = usage(root, tasks, "workspace-a");
            first.recordRouteHit("可靠检索");
            first.recordSkillRead("可靠检索");
            first.recordTurnOutcome(java.util.List.of("可靠检索"), true);
            first.close();

            SkillUsageTracker restored = usage(root, tasks, "workspace-a");
            SkillUsageTracker.SkillUsageStat stat = restored.peek("可靠检索");
            assertNotNull(stat);
            assertEquals(1, stat.routeHits.get());
            assertEquals(1, stat.reads.get());
            assertEquals(1, stat.turnSuccess.get());
            restored.close();

            SkillUsageTracker other = usage(root, tasks, "workspace-b");
            assertNull(other.peek("可靠检索"));
            other.close();
        }
    }

    @Test
    void proposalsUseInjectedRepositoryAndSurviveContextReplacement() {
        try (var root = ApplicationContexts.createRoot(new DataRoot(tempDirectory.resolve("proposal-db")));
             TaskScope tasks = root.getBean(ManagedTaskExecutor.class)
                     .openScope("skill-proposal-test", 4)) {
            AgentConfig settings = AgentConfig.getInstance();
            SkillManager skills = new SkillManager(
                    tempDirectory.resolve("managed-skills"),
                    root.getBean(ObjectMapper.class), settings);

            SkillProposalQueue first = proposals(root, tasks, "workspace-a", skills, settings);
            SkillChangeRequest request = new SkillChangeRequest();
            request.action = "create";
            request.skillName = "可恢复提案";
            request.description = "验证工作区提案持久化";
            request.content = "先执行，再核对结果。";
            String proposalId = first.submit(request);
            assertNotNull(proposalId);
            first.close();

            SkillProposalQueue restored = proposals(root, tasks, "workspace-a", skills, settings);
            assertEquals(1, restored.pendingCount());
            assertNull(restored.approve(proposalId));
            assertNotNull(skills.getSkillByName("可恢复提案"));
            restored.close();

            SkillProposalQueue other = proposals(root, tasks, "workspace-b", skills, settings);
            assertEquals(0, other.pendingCount());
            other.close();
        }
    }

    private SkillUsageTracker usage(
            org.springframework.context.ApplicationContext root,
            TaskScope tasks,
            String workspaceId) {
        return new SkillUsageTracker(workspaceId, root.getBean(JdbcTemplate.class),
                root.getBean(PlatformTransactionManager.class), AgentConfig.getInstance(),
                root.getBean(ManagedTaskExecutor.class), tasks);
    }

    private SkillProposalQueue proposals(
            org.springframework.context.ApplicationContext root,
            TaskScope tasks,
            String workspaceId,
            SkillManager skills,
            AgentConfig settings) {
        return new SkillProposalQueue(workspaceId, skills, settings,
                root.getBean(JdbcTemplate.class),
                root.getBean(PlatformTransactionManager.class),
                root.getBean(JsonCodec.class), root.getBean(ManagedTaskExecutor.class), tasks);
    }
}
