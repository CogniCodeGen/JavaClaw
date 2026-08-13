package com.javaclaw.application.agent;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.expert.KnowledgeExpert;
import com.javaclaw.application.plugin.PluginToolGateway;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.application.task.SddTaskApplicationService;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.browser.PlaywrightBrowserTools;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
import com.javaclaw.desktop.DesktopToolFactory;
import com.javaclaw.email.EmailTools;
import com.javaclaw.framework.spi.ToolContext;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ToolObjectBundle;
import com.javaclaw.mcp.McpClientManager;
import com.javaclaw.mcp.McpConfigManager;
import com.javaclaw.notification.NotificationTools;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.platform.process.ProcessRunner;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.site.SiteCredentialManager;
import com.javaclaw.skill.SkillRuntimeServices;
import com.javaclaw.system.CommandToolFactory;
import com.javaclaw.system.JShellRunner;
import com.javaclaw.system.SystemTools;
import com.javaclaw.util.ProjectAccessPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Creates a fresh, permission-scoped host tool set for each framework Run. */
public final class WorkspaceToolObjects {
    private final PlaywrightBrowserManager browsers;
    private final SiteCredentialManager siteCredentials;
    private final WorkspaceContext workspace;
    private final AgentConfig settings;
    private final EmailConfig emailSettings;
    private final NotificationConfig notificationSettings;
    private final CommandToolFactory commandTools;
    private final DesktopToolFactory desktopTools;
    private final ProcessRunner processes;
    private final KnowledgeExpert knowledge;
    private final McpConfigManager mcpConfigurations;
    private final McpClientManager mcpClients;
    private final PluginToolGateway pluginTools;
    private final SkillRuntimeServices skills;
    private final JShellRunner jshell;
    private final Supplier<SddTaskApplicationService> sddTasks;
    private final ScheduleApplicationService schedules;
    private final JsonCodec json;
    private final ModelTaskGateway modelTasks;
    private final Supplier<Object> clarificationTools;

    public WorkspaceToolObjects(
            PlaywrightBrowserManager browsers,
            SiteCredentialManager siteCredentials,
            WorkspaceContext workspace,
            AgentConfig settings,
            EmailConfig emailSettings,
            NotificationConfig notificationSettings,
            CommandToolFactory commandTools,
            DesktopToolFactory desktopTools,
            ProcessRunner processes,
            KnowledgeExpert knowledge,
            McpConfigManager mcpConfigurations,
            McpClientManager mcpClients,
            PluginToolGateway pluginTools,
            SkillRuntimeServices skills,
            JShellRunner jshell,
            Supplier<SddTaskApplicationService> sddTasks,
            ScheduleApplicationService schedules,
            JsonCodec json,
            ModelTaskGateway modelTasks,
            Supplier<Object> clarificationTools) {
        this.browsers = Objects.requireNonNull(browsers, "browsers");
        this.siteCredentials = Objects.requireNonNull(siteCredentials, "siteCredentials");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.emailSettings = Objects.requireNonNull(emailSettings, "emailSettings");
        this.notificationSettings = Objects.requireNonNull(notificationSettings, "notificationSettings");
        this.commandTools = Objects.requireNonNull(commandTools, "commandTools");
        this.desktopTools = Objects.requireNonNull(desktopTools, "desktopTools");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.mcpConfigurations = Objects.requireNonNull(mcpConfigurations, "mcpConfigurations");
        this.mcpClients = Objects.requireNonNull(mcpClients, "mcpClients");
        this.pluginTools = Objects.requireNonNull(pluginTools, "pluginTools");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.jshell = Objects.requireNonNull(jshell, "jshell");
        this.sddTasks = Objects.requireNonNull(sddTasks, "sddTasks");
        this.schedules = schedules;
        this.json = Objects.requireNonNull(json, "json");
        this.modelTasks = Objects.requireNonNull(modelTasks, "modelTasks");
        this.clarificationTools = Objects.requireNonNull(
                clarificationTools, "clarificationTools");
    }

    public ToolObjectBundle create(ToolContext context) {
        ToolCallOrigin origin = origin(context);
        Map<String, Object> capabilityTools = createCapabilityTools(origin);
        List<Object> objects = new ArrayList<>(capabilityTools.values());
        if (context.request().source().kind().equals("chat")
                || context.request().source().kind().equals("plan")) {
            objects.add(Objects.requireNonNull(
                    clarificationTools.get(), "clarification tool"));
        }
        objects.add(new com.javaclaw.code.CodeTools(origin, processes));
        objects.add(knowledge);
        objects.add(new com.javaclaw.mcp.McpTools(mcpClients, json));
        objects.add(new com.javaclaw.mcp.McpManageTools(
                mcpConfigurations, mcpClients, origin, new com.javaclaw.mcp.McpJsonImporter(json)));
        objects.add(new com.javaclaw.site.SiteCredentialTools(origin, siteCredentials));
        if (!ProjectAccessPolicy.strictIsolationEnabled()) {
            objects.add(new com.javaclaw.plugin.PluginTools(pluginTools));
        }
        objects.add(new com.javaclaw.skill.SkillTools(skills.manager(), skills.usage()));
        objects.add(new com.javaclaw.skill.SkillManageTools(
                origin, skills.manager(), settings, skills.proposals()));
        if (!ProjectAccessPolicy.strictIsolationEnabled()) {
            objects.add(new com.javaclaw.system.JShellTools(origin, skills.manager(), jshell, settings));
        }
        objects.add(new com.javaclaw.task.sdd.run.SddTaskManageTools(
                origin, Objects.requireNonNull(sddTasks.get(), "SDD task service")));
        if (context.request().source().kind().equals("sdd")) {
            String workDir = context.request().attributes().containsKey("workDir")
                    ? context.request().attributes().get("workDir").asText(null) : null;
            objects.add(new com.javaclaw.task.ValidationInspectionTools(workDir, processes));
        }
        objects.add(new com.javaclaw.media.MediaTools(
                new com.javaclaw.agent.vision.VisionPreprocessor(modelTasks, context.runId())));
        if (context.request().source().kind().equals("loop")) {
            objects.add(new com.javaclaw.loop.agent.LoopReportTool());
        }
        if (schedules != null) objects.add(new com.javaclaw.schedule.ScheduleTools(origin, schedules));
        return new ToolObjectBundle(objects, () -> closeOwned(capabilityTools.values()));
    }

    /** Complete host {@code @Tool} inventory checked before a workspace is published. */
    public static List<Class<?>> toolContractTypes() {
        List<Class<?>> result = new ArrayList<>(List.of(
                com.javaclaw.agent.expert.KnowledgeExpert.class,
                com.javaclaw.code.CodeTools.class,
                com.javaclaw.desktop.DesktopTools.class,
                com.javaclaw.email.EmailTools.class,
                com.javaclaw.loop.agent.LoopReportTool.class,
                com.javaclaw.mcp.McpManageTools.class,
                com.javaclaw.mcp.McpTools.class,
                com.javaclaw.media.MediaTools.class,
                com.javaclaw.notification.NotificationTools.class,
                com.javaclaw.plugin.PluginTools.class,
                com.javaclaw.schedule.ScheduleTools.class,
                com.javaclaw.site.SiteCredentialTools.class,
                com.javaclaw.skill.SkillManageTools.class,
                com.javaclaw.skill.SkillTools.class,
                com.javaclaw.system.CommandLineTools.class,
                com.javaclaw.system.JShellTools.class,
                com.javaclaw.system.SystemTools.class,
                com.javaclaw.task.ValidationInspectionTools.class,
                com.javaclaw.task.sdd.run.SddTaskManageTools.class));
        result.addAll(PlaywrightBrowserTools.toolContractTypes());
        return List.copyOf(result);
    }

    private Map<String, Object> createCapabilityTools(ToolCallOrigin origin) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (origin.kind() == ToolCallOrigin.Kind.INTERACTIVE) {
            // ChatService/WorkflowService already select the conversation scope on this manager.
            // Keep the shared manager alive across approval pauses and subsequent chat turns;
            // closing a per-reasoning tool bundle must not replace the page with about:blank.
            result.put("web", new PlaywrightBrowserTools(
                    browsers, siteCredentials, origin, json, false));
        } else {
            PlaywrightBrowserManager isolated = browsers.createIsolated(origin.browserScopeId());
            result.put("web", new PlaywrightBrowserTools(
                    isolated, siteCredentials, origin, json, true));
        }
        result.put("email", new EmailTools(origin, emailSettings));
        result.put("system", new SystemTools(origin, workspace.screenshotsDir()));
        if (!ProjectAccessPolicy.strictIsolationEnabled()) {
            result.put("desktop", desktopTools.create(origin, workspace.screenshotsDir()));
        }
        result.put("notification", new NotificationTools(
                origin, notificationSettings, emailSettings));
        if (!ProjectAccessPolicy.strictIsolationEnabled()) {
            result.put("command", commandTools.create(origin));
        }
        return result;
    }

    private static ToolCallOrigin origin(ToolContext context) {
        String kind = context.request().source().kind();
        String id = context.request().source().id();
        if (kind.equals("chat") || kind.equals("plan")) return ToolCallOrigin.INTERACTIVE;
        if (kind.equals("schedule")) return ToolCallOrigin.scheduled(id);
        if (kind.equals("loop") || kind.equals("sdd") || kind.equals("workflow")) {
            String workDir = context.request().attributes().containsKey("workDir")
                    ? context.request().attributes().get("workDir").asText(null) : null;
            return ToolCallOrigin.managedTask(id, workDir);
        }
        return ToolCallOrigin.UNKNOWN;
    }

    private static void closeOwned(java.util.Collection<Object> values) throws Exception {
        Set<Object> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        Exception first = null;
        List<Object> reverse = new ArrayList<>(values);
        Collections.reverse(reverse);
        for (Object value : reverse) {
            if (!(value instanceof AutoCloseable closeable) || !closed.add(value)) continue;
            try {
                closeable.close();
            } catch (Exception failure) {
                if (first == null) first = failure;
                else first.addSuppressed(failure);
            }
        }
        if (first != null) throw first;
    }
}
