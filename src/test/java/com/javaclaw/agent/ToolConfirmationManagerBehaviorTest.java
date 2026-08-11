package com.javaclaw.agent;

import com.javaclaw.agent.risk.ScopeVerdict;
import com.javaclaw.agent.risk.ToolScopeAssessor;
import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.CredentialCipher;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.config.SqlPropertyStore;
import com.javaclaw.config.ToolReviewMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolConfirmationManagerBehaviorTest {

    private static AgentConfig config;

    private ManagerState previousState;
    private RecordingPort port;

    @BeforeAll
    static void createConfig() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tool-confirmation-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE app_properties(
                    workspace_id VARCHAR(128), namespace VARCHAR(128), prop_key VARCHAR(256),
                    prop_value CLOB, updated_at TIMESTAMP,
                    PRIMARY KEY(workspace_id, namespace, prop_key))
                """);
        SqlPropertyStore store = new SqlPropertyStore(
                jdbc, new DataSourceTransactionManager(dataSource), () -> "test-workspace");
        DatabaseAccess database = new DatabaseAccess() {
            @Override
            public Connection open() throws SQLException {
                return dataSource.getConnection();
            }

            @Override
            public String description() {
                return "in-memory confirmation test database";
            }
        };
        CredentialCipher identityCipher = new CredentialCipher() {
            @Override
            public String encrypt(String plainText) {
                return plainText;
            }

            @Override
            public String decrypt(String encryptedText) {
                return encryptedText;
            }

            @Override
            public boolean isEncrypted(String value) {
                return false;
            }

            @Override
            public void warmUp() {
            }
        };
        config = new AgentConfig(store, database, identityCipher);
    }

    @BeforeEach
    void resetManager() throws ReflectiveOperationException {
        previousState = ManagerState.capture();
        port = new RecordingPort();
        ToolConfirmationManager.setEnabled(true);
        ToolConfirmationManager.setPort(port);
        ToolConfirmationManager.setScopeAssessor(null);
        setStaticField("settings", null);
        setStaticField("authorizedScheduledOrigin", null);
        taskAllowlist().clear();
    }

    @AfterEach
    void restoreManager() throws ReflectiveOperationException {
        previousState.restore();
    }

    @Test
    void globalSwitchAndReviewModesSelectTheExpectedGate() {
        assertTrue(ToolConfirmationManager.isEnabled());
        assertSame(port, ToolConfirmationManager.getPort());
        assertFalse(ToolConfirmationManager.requiresConfirmation("unregistered_tool"));
        assertTrue(ToolConfirmationManager.requiresConfirmation("sys_file_write"));

        ToolConfirmationManager.setEnabled(false);
        assertFalse(ToolConfirmationManager.requiresConfirmation("sys_file_write"));
        assertTrue(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.INTERACTIVE, "sys_file_write", "write"));
        assertEquals(0, port.confirmations.size());

        ToolConfirmationManager.setEnabled(true);
        assertTrue(ToolConfirmationManager.requestConfirmation("unregistered_tool", "noop"));
        assertEquals(0, port.confirmations.size());

        useMode(ToolReviewMode.AUTO);
        assertFalse(ToolConfirmationManager.requiresConfirmation("sys_file_write"));
        assertTrue(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.INTERACTIVE, "sys_file_write", "write"));
        assertEquals(0, port.confirmations.size());

        port.decision = ConfirmDecision.ALLOW_ONCE;
        assertEquals(ToolConfirmationManager.ConfirmOutcome.ALLOWED_HUMAN,
                ToolConfirmationManager.requestHighRiskCommandConfirmation(
                        null, "cmd_execute", "rm target"));
        assertTrue(ToolConfirmationManager.requestStandaloneConfirmation(
                "loop_verify", "run verifier"));
        assertEquals(2, port.confirmations.size());

        port.decision = ConfirmDecision.DENY;
        assertEquals(ToolConfirmationManager.ConfirmOutcome.DENIED,
                ToolConfirmationManager.requestHighRiskCommandConfirmation(
                        ToolCallOrigin.INTERACTIVE, "cmd_execute", "rm target"));
        assertFalse(ToolConfirmationManager.requestStandaloneConfirmation(
                "loop_verify", "run verifier"));

        assertFalse(ToolConfirmationManager.ConfirmOutcome.DENIED.isAllow());
        assertTrue(ToolConfirmationManager.ConfirmOutcome.ALLOWED_AUTO.isAllow());
        assertTrue(ToolConfirmationManager.ConfirmOutcome.ALLOWED_HUMAN.isAllow());
    }

    @Test
    void explicitInteractionsRequireAnAvailablePortAndPreserveRequestData() {
        List<ChoiceOption> options = List.of(
                new ChoiceOption("keep", "保留", "保留当前会话"),
                new ChoiceOption("drop", "删除", ""));

        ToolConfirmationManager.setPort(null);
        assertFalse(ToolConfirmationManager.requestExplicitUserConfirmation(
                "登录", "请完成登录", 15));
        assertNull(ToolConfirmationManager.requestExplicitUserChoice(
                "选择", "请选择", options, 20));

        ToolConfirmationManager.setPort(port);
        port.available = false;
        assertFalse(ToolConfirmationManager.requestExplicitUserConfirmation(
                "登录", "请完成登录", 15));
        assertNull(ToolConfirmationManager.requestExplicitUserChoice(
                "选择", "请选择", options, 20));

        port.available = true;
        port.decision = ConfirmDecision.ALLOW_ALL;
        port.choice = "keep";
        assertTrue(ToolConfirmationManager.requestExplicitUserConfirmation(
                "登录", "请完成登录", 15));
        assertEquals("keep", ToolConfirmationManager.requestExplicitUserChoice(
                "选择", "请选择", options, 20));
        ConfirmRequest confirmation = port.confirmations.getFirst();
        assertEquals("登录", confirmation.toolName());
        assertEquals("需要用户操作", confirmation.riskLabel());
        assertEquals("请完成登录", confirmation.description());
        assertEquals(ConfirmKind.CONFIRM, confirmation.kind());
        assertEquals(15, confirmation.timeoutSeconds());
        assertFalse(confirmation.managedTask());
        ChoiceRequest choice = port.choices.getFirst();
        assertEquals("选择", choice.title());
        assertEquals(options, choice.options());
        assertEquals(20, choice.timeoutSeconds());

        port.decision = ConfirmDecision.DENY;
        assertFalse(ToolConfirmationManager.requestExplicitUserConfirmation(
                "登录", "请完成登录", 15));
        port.confirmFailure = new IllegalStateException("closed");
        assertFalse(ToolConfirmationManager.requestExplicitUserConfirmation(
                "登录", "请完成登录", 15));
        port.confirmFailure = null;
        port.chooseFailure = new IllegalStateException("closed");
        assertNull(ToolConfirmationManager.requestExplicitUserChoice(
                "选择", "请选择", null, 0));
    }

    @Test
    void smartAndManualModesApplyRiskLevelsAndOriginTimeouts() {
        assertTrue(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.INTERACTIVE, "sys_file_mkdir", "mkdir"));
        assertEquals(new ToastRequest("sys_file_mkdir", "mkdir"), port.notifications.getFirst());
        assertEquals(0, port.confirmations.size());

        port.decision = ConfirmDecision.ALLOW_ONCE;
        assertTrue(ToolConfirmationManager.requestConfirmation(
                null, "sys_file_write", "write"));
        ConfirmRequest unknown = port.confirmations.getLast();
        assertEquals("高风险", unknown.riskLabel());
        assertEquals(60, unknown.timeoutSeconds());
        assertFalse(unknown.managedTask());

        assertTrue(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.managedTask("task-timeout", null), "git_commit", "commit"));
        ConfirmRequest managed = port.confirmations.getLast();
        assertEquals(600, managed.timeoutSeconds());
        assertTrue(managed.managedTask());

        assertTrue(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.INTERACTIVE, "sys_file_delete", "delete"));
        ConfirmRequest destructive = port.confirmations.getLast();
        assertEquals(ConfirmKind.DOUBLE_CONFIRM, destructive.kind());
        assertEquals("不可逆·高风险", destructive.riskLabel());
        assertEquals("确认", destructive.keyword());

        useMode(ToolReviewMode.MANUAL);
        config.setConfirmationTimeoutDefault(17);
        config.setConfirmationTimeoutManaged(29);
        assertTrue(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.INTERACTIVE, "sys_file_mkdir", "mkdir"));
        ConfirmRequest upgradedNotification = port.confirmations.getLast();
        assertEquals(ConfirmKind.CONFIRM, upgradedNotification.kind());
        assertEquals("高风险", upgradedNotification.riskLabel());
        assertEquals(17, upgradedNotification.timeoutSeconds());
        assertFalse(upgradedNotification.managedTask());

        assertTrue(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.managedTask("manual-task", "/work"),
                "sys_file_write", "write"));
        ConfirmRequest manualManaged = port.confirmations.getLast();
        assertEquals(29, manualManaged.timeoutSeconds());
        assertFalse(manualManaged.managedTask());

        port.available = false;
        assertFalse(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.INTERACTIVE, "sys_file_write", "write"));
        ToolConfirmationManager.setPort(null);
        assertFalse(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.INTERACTIVE, "sys_file_write", "write"));
    }

    @Test
    void allowAllIsBoundToOneManagedTaskAndCanBeRevoked() {
        ToolCallOrigin task = ToolCallOrigin.managedTask("task-a", null);
        port.decision = ConfirmDecision.ALLOW_ALL;
        assertTrue(ToolConfirmationManager.requestConfirmation(task, "sys_file_write", "first"));
        assertEquals(1, port.confirmations.size());
        assertEquals(1, port.notifications.size());

        port.decision = ConfirmDecision.DENY;
        assertTrue(ToolConfirmationManager.requestConfirmation(task, "git_commit", "second"));
        assertEquals(1, port.confirmations.size());

        assertFalse(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.managedTask("task-b", null), "git_commit", "other task"));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.INTERACTIVE, "git_commit", "interactive"));
        assertEquals(3, port.confirmations.size());

        ToolConfirmationManager.clearTaskAllowlist(null);
        ToolConfirmationManager.clearTaskAllowlist("task-a");
        assertFalse(ToolConfirmationManager.requestConfirmation(task, "git_commit", "after clear"));

        port.decision = ConfirmDecision.ALLOW_ALL;
        assertTrue(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.INTERACTIVE, "git_commit", "not recordable"));
        port.decision = ConfirmDecision.DENY;
        assertFalse(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.INTERACTIVE, "git_commit", "still prompts"));

        port.decision = ConfirmDecision.ALLOW_ALL;
        assertTrue(ToolConfirmationManager.requestConfirmation(task, "git_commit", "record again"));
        useMode(ToolReviewMode.MANUAL);
        port.decision = ConfirmDecision.DENY;
        assertFalse(ToolConfirmationManager.requestConfirmation(task, "git_commit", "manual"));
    }

    @Test
    void scheduledAuthorizationUsesRunTokenIdentityInsteadOfTaskIdEquality() {
        ToolCallOrigin authorized = ToolConfirmationManager.beginAuthorizedScheduledRun(
                "nightly", true);
        ToolCallOrigin equalButDifferentToken = ToolCallOrigin.scheduled("nightly");
        port.decision = ConfirmDecision.DENY;

        assertTrue(ToolConfirmationManager.requestConfirmation(
                authorized, "sys_file_write", "authorized"));
        assertEquals(0, port.confirmations.size());
        assertEquals(1, port.notifications.size());
        assertFalse(ToolConfirmationManager.requestConfirmation(
                equalButDifferentToken, "sys_file_write", "stale token"));

        ToolConfirmationManager.endScheduledRun();
        assertFalse(ToolConfirmationManager.requestConfirmation(
                authorized, "sys_file_write", "window closed"));

        ToolCallOrigin unauthorized = ToolConfirmationManager.beginAuthorizedScheduledRun(
                "nightly", false);
        assertFalse(ToolConfirmationManager.requestConfirmation(
                unauthorized, "sys_file_write", "not authorized"));
        ToolCallOrigin missingId = ToolConfirmationManager.beginAuthorizedScheduledRun(null, true);
        assertFalse(ToolConfirmationManager.requestConfirmation(
                missingId, "sys_file_write", "missing id"));

        ToolCallOrigin withoutUi = ToolConfirmationManager.beginAuthorizedScheduledRun(
                "headless", true);
        ToolConfirmationManager.setPort(null);
        assertTrue(ToolConfirmationManager.requestConfirmation(
                withoutUi, "sys_file_write", "headless authorization"));

        ToolConfirmationManager.setPort(port);
        useMode(ToolReviewMode.MANUAL);
        port.decision = ConfirmDecision.DENY;
        assertFalse(ToolConfirmationManager.requestConfirmation(
                withoutUi, "sys_file_write", "manual review"));
    }

    @Test
    void scopeApprovalRequiresModelVerdictAndDeterministicPathContainment() {
        String root = "/workspace/project";
        ToolCallOrigin task = ToolCallOrigin.managedTask("scoped", root);
        port.decision = ConfirmDecision.DENY;

        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "sys_file_write", "no assessor"));

        AtomicInteger assessments = new AtomicInteger();
        ToolConfirmationManager.setScopeAssessor((tool, description, workDir) -> {
            assessments.incrementAndGet();
            return null;
        });
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "sys_file_write", "null verdict"));

        ToolConfirmationManager.setScopeAssessor((tool, description, workDir) ->
                ScopeVerdict.outOfScope("uncertain"));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "sys_file_write", "outside verdict"));

        ToolConfirmationManager.setScopeAssessor((tool, description, workDir) ->
                new ScopeVerdict(true, List.of(), ""));
        assertTrue(ToolConfirmationManager.requestConfirmation(
                task, "sys_file_write", "no explicit path"));
        assertTrue(port.notifications.getLast().message().contains("影响范围限于任务目录"));

        ToolConfirmationManager.setScopeAssessor((tool, description, workDir) ->
                new ScopeVerdict(true,
                        List.of("src/Main.java", root + "/target/result.txt", "  "),
                        "仅修改构建输出"));
        assertTrue(ToolConfirmationManager.requestConfirmation(
                task, "sys_file_write", "inside"));
        assertTrue(port.notifications.getLast().message().contains("仅修改构建输出"));

        ToolConfirmationManager.setScopeAssessor((tool, description, workDir) ->
                new ScopeVerdict(true, List.of("../outside.txt"), "claimed inside"));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "sys_file_write", "relative escape"));

        ToolConfirmationManager.setScopeAssessor((tool, description, workDir) ->
                new ScopeVerdict(true, List.of("/etc/passwd"), "claimed inside"));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "sys_file_write", "absolute escape"));

        ToolConfirmationManager.setScopeAssessor((tool, description, workDir) ->
                new ScopeVerdict(true, List.of("bad\0path"), "invalid path"));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "sys_file_write", "invalid path"));

        ToolConfirmationManager.setScopeAssessor((tool, description, workDir) -> {
            throw new IllegalArgumentException("model unavailable");
        });
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "sys_file_write", "assessor failed"));

        AtomicInteger disabledCalls = new AtomicInteger();
        ToolConfirmationManager.setScopeAssessor((tool, description, workDir) -> {
            disabledCalls.incrementAndGet();
            return new ScopeVerdict(true, List.of(), "inside");
        });
        useMode(ToolReviewMode.SMART);
        config.setTaskRiskAutoApproveEnabled(false);
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "sys_file_write", "disabled"));
        assertEquals(0, disabledCalls.get());

        config.setTaskRiskAutoApproveEnabled(true);
        assertFalse(ToolConfirmationManager.requestConfirmation(
                new ToolCallOrigin(ToolCallOrigin.Kind.MANAGED_TASK, "blank-dir", " "),
                "sys_file_write", "blank directory"));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                ToolCallOrigin.managedTask(null, root), "sys_file_write", "missing task id"));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "git_commit", "not directory scoped"));
        assertEquals(0, disabledCalls.get());
    }

    @Test
    void commandDescriptionEnablesOnlyDeterministicallyReadOnlyCommands() {
        ToolCallOrigin task = ToolCallOrigin.managedTask("command", "/workspace/project");
        port.decision = ConfirmDecision.DENY;

        String description = ToolConfirmationManager.buildCommandDescription(
                "ls /tmp | grep cache", "/workspace/project");
        assertEquals("命令: ls /tmp | grep cache | 目录: /workspace/project", description);
        assertTrue(ToolConfirmationManager.requestConfirmation(task, "cmd_execute", description));
        assertTrue(port.notifications.getLast().message().contains("只读命令"));

        ToolConfirmationManager.setScopeAssessor((tool, detail, workDir) ->
                new ScopeVerdict(true, List.of("target/output.txt"), "构建目录内"));
        String mutating = ToolConfirmationManager.buildCommandDescription(
                "touch target/output.txt", "/workspace/project");
        assertTrue(ToolConfirmationManager.requestConfirmation(task, "cmd_execute", mutating));
        assertTrue(port.notifications.getLast().message().contains("构建目录内"));

        ToolConfirmationManager.setScopeAssessor(null);
        assertFalse(ToolConfirmationManager.requestConfirmation(task, "cmd_execute", null));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "cmd_execute", "运行 ls"));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "cmd_execute", "命令: ls"));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "cmd_execute", "命令:    | 目录: /workspace/project"));
        assertFalse(ToolConfirmationManager.requestConfirmation(
                task, "cmd_execute",
                ToolConfirmationManager.buildCommandDescription(
                        "cat file > output", "/workspace/project")));
    }

    @Test
    void originsAndRiskRegistryExposeConservativeDefaults() {
        assertSame(ToolCallOrigin.SCHEDULED, ToolCallOrigin.SCHEDULED);
        assertNull(ToolCallOrigin.scheduled(null).taskId());
        assertNull(ToolCallOrigin.scheduled("  ").taskId());
        assertEquals("schedule", ToolCallOrigin.scheduled("schedule").taskId());
        assertTrue(ToolCallOrigin.managedTask("task", " /tmp ").isManagedTask());
        assertFalse(ToolCallOrigin.INTERACTIVE.isManagedTask());
        assertFalse(ToolCallOrigin.managedTask(null, null).isManagedTask());

        assertEquals("managed:task", ToolCallOrigin.managedTask("task", null).browserScopeId());
        assertEquals("managed:default",
                new ToolCallOrigin(ToolCallOrigin.Kind.MANAGED_TASK, null, null).browserScopeId());
        assertEquals("scheduled:schedule", ToolCallOrigin.scheduled("schedule").browserScopeId());
        assertEquals("scheduled:default", ToolCallOrigin.SCHEDULED.browserScopeId());
        assertEquals("interactive:default", ToolCallOrigin.INTERACTIVE.browserScopeId());
        assertEquals("unknown:default", ToolCallOrigin.UNKNOWN.browserScopeId());

        assertEquals(ToolRiskLevel.CONFIRM, ToolRiskRegistry.levelOf("sys_file_write"));
        assertNull(ToolRiskRegistry.levelOf("unknown"));
        assertTrue(ToolRiskRegistry.isManaged("sys_file_delete"));
        assertFalse(ToolRiskRegistry.isManaged("unknown"));
        assertEquals("文件删除", ToolRiskRegistry.labelOf("sys_file_delete"));
        assertEquals("unknown", ToolRiskRegistry.labelOf("unknown"));
        assertTrue(ToolRiskRegistry.isDirScopedTool("code_edit"));
        assertFalse(ToolRiskRegistry.isDirScopedTool("git_commit"));
        assertTrue(ToolRiskRegistry.allManagedTools().contains("schedule_create"));
        assertTrue(ToolRiskRegistry.dirScopedTools().contains("cmd_execute"));
        assertThrows(UnsupportedOperationException.class,
                () -> ToolRiskRegistry.allManagedTools().remove("sys_file_write"));
    }

    private static void useMode(ToolReviewMode mode) {
        config.setToolReviewMode(mode);
        config.setTaskRiskAutoApproveEnabled(true);
        ToolConfirmationManager.configure(config);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> taskAllowlist() throws ReflectiveOperationException {
        Field field = ToolConfirmationManager.class.getDeclaredField("TASK_ALLOW_ALL");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    private static Object staticField(String name) throws ReflectiveOperationException {
        Field field = ToolConfirmationManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(String name, Object value) throws ReflectiveOperationException {
        Field field = ToolConfirmationManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private record ManagerState(
            boolean enabled,
            AgentConfig settings,
            ToolScopeAssessor assessor,
            UserInteractionPort port,
            ToolCallOrigin authorizedOrigin,
            Set<String> allowlist) {

        static ManagerState capture() throws ReflectiveOperationException {
            return new ManagerState(
                    ToolConfirmationManager.isEnabled(),
                    (AgentConfig) staticField("settings"),
                    (ToolScopeAssessor) staticField("scopeAssessor"),
                    ToolConfirmationManager.getPort(),
                    (ToolCallOrigin) staticField("authorizedScheduledOrigin"),
                    Set.copyOf(taskAllowlist()));
        }

        void restore() throws ReflectiveOperationException {
            ToolConfirmationManager.setEnabled(enabled);
            ToolConfirmationManager.setPort(port);
            ToolConfirmationManager.setScopeAssessor(assessor);
            setStaticField("settings", settings);
            setStaticField("authorizedScheduledOrigin", authorizedOrigin);
            Set<String> current = taskAllowlist();
            current.clear();
            current.addAll(allowlist);
        }
    }

    private static final class RecordingPort implements UserInteractionPort {
        private final List<ConfirmRequest> confirmations = new ArrayList<>();
        private final List<ChoiceRequest> choices = new ArrayList<>();
        private final List<ToastRequest> notifications = new ArrayList<>();
        private boolean available = true;
        private ConfirmDecision decision = ConfirmDecision.ALLOW_ONCE;
        private String choice;
        private RuntimeException confirmFailure;
        private RuntimeException chooseFailure;

        @Override
        public boolean confirm(ConfirmRequest request) {
            return confirmEx(request).isAllow();
        }

        @Override
        public ConfirmDecision confirmEx(ConfirmRequest request) {
            confirmations.add(request);
            if (confirmFailure != null) throw confirmFailure;
            return decision;
        }

        @Override
        public String choose(ChoiceRequest request) {
            choices.add(request);
            if (chooseFailure != null) throw chooseFailure;
            return choice;
        }

        @Override
        public void notify(ToastRequest request) {
            notifications.add(request);
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }
}
