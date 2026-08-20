package com.javaclaw.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.application.agent.WorkspaceToolObjects;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.diagnostics.TraceRecorder;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ToolContext;
import com.javaclaw.framework.spi.ToolContract;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.platform.spring.WorkspaceSpringContextFactory;
import com.javaclaw.plugin.PluginManager;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.system.CommandWhitelistManager;
import com.javaclaw.system.CommandLineTools;
import com.javaclaw.system.CommandToolFactory;
import com.javaclaw.system.JShellRunner;
import com.javaclaw.system.JShellTools;
import com.javaclaw.ui.javafx.settings.SettingsCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executable acceptance inventory for the user-visible 3.0 surface. */
class FunctionalAcceptanceInventoryTest {

    private static final Set<String> EXPECTED_MODE_IDS = Set.of(
            "chat", "plan", "loop", "workflow", "shell", "task", "workflow-center");
    private static final Set<String> EXPECTED_TOOL_GROUPS = Set.of(
            "agents", "coding", "command", "desktop", "dynamic_task", "email",
            "knowledge", "mcp", "media", "notification", "plugins", "schedule",
            "skill", "system", "task_manage", "web");
    private static final Set<String> TOOL_PERMISSIONS = Set.of(
            "tool.read", "tool.execute", "interaction.request");

    @TempDir
    Path temporaryDirectory;

    private AnnotationConfigApplicationContext rootContext;
    private ApplicationKernel kernel;

    @AfterEach
    void closeRuntime() {
        if (kernel != null) kernel.close();
        if (rootContext != null) rootContext.close();
    }

    @Test
    void productionRuntimeRegistersTheSevenAcceptedModes() {
        rootContext = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data")));
        ApplicationContexts.registerDesktopInfrastructure(rootContext);
        kernel = createKernel(rootContext);
        kernel.initialize();

        Set<String> actual = kernel.current().modeRegistry().list().stream()
                .map(mode -> mode.id())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertEquals(EXPECTED_MODE_IDS, actual);
    }

    @Test
    void settingsAndFxmlInventoriesMatchTheAcceptedDesktopSurface() throws Exception {
        assertEquals(14, SettingsCategory.values().length);
        assertEquals(Set.of(
                        "MODEL", "TIERED_MODEL", "EMBEDDING", "AGENT", "GEPA",
                        "SKILL_EVOLUTION", "MCP", "SITE", "APPEARANCE", "FONT",
                        "GENERAL", "TEST_DATA", "EMAIL", "NOTIFICATION"),
                java.util.Arrays.stream(SettingsCategory.values())
                        .map(Enum::name).collect(java.util.stream.Collectors.toSet()));

        Path fxmlRoot = Path.of(System.getProperty("user.dir"), "src/main/resources/fxml");
        try (var files = Files.walk(fxmlRoot)) {
            assertEquals(147, files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".fxml")).count());
        }
    }

    @Test
    void everyAcceptedHostToolHasExplicitAndConsistentAuthorizationMetadata() {
        List<Class<?>> types = com.javaclaw.platform.spring.WorkspaceSpringConfiguration
                .hostToolContractTypes();
        assertEquals(24, types.size());
        com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.validateContracts(types);

        List<String> violations = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        int methodCount = 0;
        for (Class<?> type : types) {
            ToolContract classContract = AnnotationUtils.findAnnotation(type, ToolContract.class);
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = AnnotationUtils.findAnnotation(method, Tool.class);
                if (tool == null) continue;
                methodCount++;
                String name = tool.name().isBlank() ? method.getName() : tool.name();
                if (!names.add(name)) violations.add("重复工具名: " + name);
                if (tool.description().isBlank()) violations.add("工具描述为空: " + name);
                ToolContract contract = AnnotationUtils.findAnnotation(method, ToolContract.class);
                if (contract == null) contract = classContract;
                if (contract == null) {
                    violations.add("缺少 ToolContract: " + type.getName() + "#" + name);
                    continue;
                }
                groups.add(contract.group());
                Set<String> permissions = Set.of(contract.permissions());
                if (!TOOL_PERMISSIONS.containsAll(permissions)) {
                    violations.add("未知权限: " + name + " " + permissions);
                }
                boolean readOnly = permissions.equals(Set.of("tool.read"));
                if (readOnly != contract.idempotent()) {
                    violations.add("读写/幂等元数据不一致: " + name);
                }
            }
        }

        assertEquals(167, methodCount);
        assertEquals(167, names.size());
        assertEquals(EXPECTED_TOOL_GROUPS, groups);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void everyStrictlyExposedHostToolCanBeInvokedThroughTheProductionBridgeSafely() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("javaclaw.fx.tests"),
                "浏览器直接调用验收只在 ui-test profile 的真实桌面权限下运行");
        boolean previousConfirmationState = ToolConfirmationManager.isEnabled();
        var previousPort = ToolConfirmationManager.getPort();
        rootContext = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("direct-tool-data")));
        ApplicationContexts.registerDesktopInfrastructure(rootContext);
        kernel = createKernel(rootContext);
        kernel.initialize();

        AgentConfig settings = kernel.current().agentConfig();
        settings.setToolReviewMode(ToolReviewMode.MANUAL);
        EmailConfig email = rootContext.getBean(EmailConfig.class);
        email.setImapHost("127.0.0.1");
        email.setImapPort(1);
        email.setSmtpHost("127.0.0.1");
        email.setSmtpPort(1);
        ToolConfirmationManager.configure(settings);
        ToolConfirmationManager.setEnabled(true);
        ToolConfirmationManager.setPort(null);

        String workspaceId = kernel.current().context().workspaceId();
        RunId runId = RunId.random();
        RunScope scope = new RunScope(workspaceId, "acceptance", "direct-tools");
        Instant deadline = Instant.now().plusSeconds(60);
        CancellationToken active = () -> false;
        SpringAiAnnotatedToolRegistry registry =
                rootContext.getBean(SpringAiAnnotatedToolRegistry.class);
        List<List<FrameworkTool>> bundles = List.of(
                createTools(registry, runId, scope, active, deadline, InvocationSource.chat()),
                createTools(registry, runId, scope, active, deadline,
                        InvocationSource.sdd("direct-tools")),
                createTools(registry, runId, scope, active, deadline,
                        InvocationSource.loop("direct-tools")));
        List<FrameworkTool> tools = bundles.stream().flatMap(List::stream).toList();
        Map<String, FrameworkTool> uniqueTools = new LinkedHashMap<>();
        tools.forEach(tool -> uniqueTools.putIfAbsent(tool.descriptor().name(), tool));

        Map<String, String> failures = new LinkedHashMap<>();
        Set<String> invoked = new LinkedHashSet<>();
        try {
            assertEquals(List.of(137, 140, 137),
                    bundles.stream().map(List::size).toList(),
                    "交互、SDD 与 Loop Run 的专属工具装配数量不符");
            assertEquals(142, uniqueTools.size(),
                    "严格隔离仅应排除 desktop/command/jshell/plugin 共 25 个工具");
            for (FrameworkTool tool : uniqueTools.values()) {
                String name = tool.descriptor().name();
                try {
                    JsonNode result = tool.execute(
                            deterministicArguments(tool.descriptor().inputSchema()),
                            new ToolExecutionContext(runId, "probe-" + name, active, deadline));
                    if (result == null) failures.put(name, "returned null");
                    invoked.add(name);
                } catch (Exception failure) {
                    failures.put(name, failure.getClass().getSimpleName() + ": " + failure.getMessage());
                }
            }
        } finally {
            Exception closeFailure = null;
            for (FrameworkTool tool : tools) {
                try {
                    tool.close();
                } catch (Exception failure) {
                    if (closeFailure == null) closeFailure = failure;
                    else closeFailure.addSuppressed(failure);
                }
            }
            ToolConfirmationManager.setPort(previousPort);
            ToolConfirmationManager.setEnabled(previousConfirmationState);
            if (closeFailure != null) throw closeFailure;
        }

        assertEquals(142, invoked.size(), () -> "未完成直接调用: " + failures);
        assertTrue(failures.isEmpty(), () -> "工具桥直接调用异常: " + failures);
    }

    @Test
    void everyStrictlyDisabledHostToolHasAnExecutableSafeRejectionPath() {
        Assumptions.assumeTrue(Boolean.getBoolean("javaclaw.fx.tests"),
                "桌面能力探测只在 ui-test profile 的真实桌面权限下运行");
        boolean previousConfirmationState = ToolConfirmationManager.isEnabled();
        var previousPort = ToolConfirmationManager.getPort();
        rootContext = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("disabled-tool-data")));
        ApplicationContexts.registerDesktopInfrastructure(rootContext);
        kernel = createKernel(rootContext);
        kernel.initialize();

        AgentConfig settings = kernel.current().agentConfig();
        ToolReviewMode previousMode = settings.getToolReviewMode();
        ToolConfirmationManager.configure(settings);
        ToolConfirmationManager.setEnabled(true);
        ToolConfirmationManager.setPort(null);
        settings.setToolReviewMode(ToolReviewMode.MANUAL);

        Map<String, String> results = new LinkedHashMap<>();
        try {
            CommandLineTools command = rootContext.getBean(CommandToolFactory.class)
                    .create(ToolCallOrigin.INTERACTIVE);
            results.put("cmd_execute", command.executeCommand("printf acceptance", temporaryDirectory.toString(), 1));
            results.put("cmd_whitelist_list", command.listWhitelist());
            results.put("cmd_whitelist_add", command.addToWhitelist("", "", "acceptance"));
            results.put("cmd_whitelist_remove", command.removeFromWhitelist(""));
            results.put("cmd_session_open", command.openSession(temporaryDirectory.toString()));
            results.put("cmd_session_exec", command.execInSession("missing", "printf acceptance", 1));
            results.put("cmd_session_input", command.sendInputToSession("missing", "", 0));
            results.put("cmd_session_read", command.readFromSession("missing", 0));
            results.put("cmd_session_close", command.closeSession("missing"));
            results.put("cmd_session_list", command.listSessions());

            JShellTools jshell = new JShellTools(
                    ToolCallOrigin.INTERACTIVE,
                    kernel.current().skillRuntimeServices().manager(),
                    rootContext.getBean(JShellRunner.class), settings);
            results.put("jshell_exec", jshell.execJava("1 + 1", 1));
            results.put("jshell_run_script", jshell.runSkillScript("missing", "missing.jsh", "", 1));

            com.javaclaw.plugin.PluginTools plugins = new com.javaclaw.plugin.PluginTools(
                    rootContext.getBean(PluginManager.class));
            results.put("plugin_list_tools", plugins.listTools());
            results.put("plugin_call_tool", plugins.callTool("missing", "missing", "{}"));

            com.javaclaw.desktop.DesktopTools desktop =
                    rootContext.getBean(com.javaclaw.desktop.DesktopToolFactory.class)
                            .create(ToolCallOrigin.INTERACTIVE,
                                    kernel.current().context().screenshotsDir());
            results.put("desktop_probe", desktop.probe());
            results.put("desktop_launch", desktop.launch("JavaClaw acceptance", null));
            results.put("desktop_list_windows", desktop.listWindows());
            results.put("desktop_activate", desktop.activate("JavaClaw acceptance"));
            results.put("desktop_capture", desktop.capture(null));
            results.put("desktop_inspect", desktop.inspect(null));
            results.put("desktop_click_ref", desktop.clickRef("missing", 1));
            results.put("desktop_type_ref", desktop.typeRef("missing", "acceptance"));
            results.put("desktop_click", desktop.click(0, 0, "left", 1));
            results.put("desktop_type", desktop.type("acceptance"));
            results.put("desktop_key", desktop.key("escape"));
        } finally {
            settings.setToolReviewMode(previousMode);
            ToolConfirmationManager.setPort(previousPort);
            ToolConfirmationManager.setEnabled(previousConfirmationState);
        }

        assertEquals(25, results.size());
        results.forEach((name, result) -> {
            assertTrue(result != null && !result.isBlank(), () -> name + " 返回空结果");
            assertTrue(result.startsWith("[" + name + "]"),
                    () -> name + " 返回了错误的工具标签: " + result);
        });
    }

    private static List<FrameworkTool> createTools(
            SpringAiAnnotatedToolRegistry registry,
            RunId runId,
            RunScope scope,
            CancellationToken cancellation,
            Instant deadline,
            InvocationSource source) {
        RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("system.default"))
                .source(source)
                .scope(scope)
                .input(InputBlock.text("deterministic host-tool acceptance probe"))
                .permissionCeiling(PermissionSet.UNRESTRICTED)
                .build();
        return registry.create(new ToolContext(
                runId, scope, PermissionSet.UNRESTRICTED, cancellation, deadline, request));
    }

    private static ObjectNode deterministicArguments(JsonNode schema) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) return result;
        properties.fields().forEachRemaining(entry ->
                result.set(entry.getKey(), deterministicValue(entry.getValue())));
        return result;
    }

    private static JsonNode deterministicValue(JsonNode schema) {
        JsonNode type = schema.get("type");
        String typeName = type == null ? "" : type.isArray()
                ? firstNonNullType((ArrayNode) type) : type.asText();
        return switch (typeName) {
            case "boolean" -> BooleanNode.FALSE;
            case "integer" -> IntNode.valueOf(0);
            case "number" -> DoubleNode.valueOf(0);
            case "array" -> JsonNodeFactory.instance.arrayNode();
            case "object" -> JsonNodeFactory.instance.objectNode();
            case "null" -> NullNode.instance;
            default -> TextNode.valueOf("");
        };
    }

    private static String firstNonNullType(ArrayNode types) {
        for (JsonNode candidate : types) {
            if (!candidate.asText().equals("null")) return candidate.asText();
        }
        return "null";
    }

    private static ApplicationKernel createKernel(AnnotationConfigApplicationContext context) {
        return new ApplicationKernel(
                context.getBean(PlaywrightBrowserManager.class),
                () -> { }, () -> { }, () -> { },
                context.getBean(WorkspaceSpringContextFactory.class),
                context.getBean(PluginManager.class),
                context.getBean(WorkspaceManager.class),
                context.getBean(DataManager.class),
                context.getBean(TraceRecorder.class),
                context.getBean(AgentConfig.class),
                context.getBean(EmailConfig.class),
                context.getBean(NotificationConfig.class),
                context.getBean(CommandWhitelistManager.class));
    }
}
