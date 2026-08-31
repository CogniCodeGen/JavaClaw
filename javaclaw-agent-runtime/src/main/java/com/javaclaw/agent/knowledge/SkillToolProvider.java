package com.javaclaw.agent.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.SkillManifests;
import com.javaclaw.agent.tool.ToolHandler;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolProvider;
import com.javaclaw.agent.tool.ToolRisk;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** H2 Skill 按需读取与脚本执行；目录固定到 Turn，启用状态和 revision 则在每次执行前复核。 */
public final class SkillToolProvider implements ToolProvider {
    private final KnowledgeUseCases knowledge;
    private final SandboxPolicy ceiling;
    private final SkillScriptGateway scripts;
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["skillId","revision"],
             "properties":{"skillId":{"type":"string","minLength":1,"maxLength":200},
             "revision":{"type":"integer","minimum":1}}}
            """;
    private static final String RESOURCE_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["skillId","revision","path"],
             "properties":{"skillId":{"type":"string","minLength":1,"maxLength":200},
             "revision":{"type":"integer","minimum":1},"path":{"type":"string","minLength":1,"maxLength":240}}}
            """;
    private static final ToolDescriptor READ = new ToolDescriptor("skill_read", "完整读取目录中选中的 Skill 指令及资源清单。", SCHEMA);
    private static final ToolDescriptor RESOURCE =
            new ToolDescriptor("skill_resource_read", "完整读取当前 Skill 版本中的一个引用资源。", RESOURCE_SCHEMA);
    private static final ToolDescriptor SCRIPT =
            new ToolDescriptor("skill_script_run", "在沙箱子 JVM 执行选中 Skill 声明的 Java/JShell 脚本。", RESOURCE_SCHEMA);

    /** 未装配沙箱 Worker 时只发布读取能力，不能降级在主 JVM 执行。 */
    public SkillToolProvider(KnowledgeUseCases knowledge, SandboxPolicy ceiling) {
        this(knowledge, ceiling, null);
    }

    /** scripts 只能由服务端装配的固定入口实现；第三方 Skill 只提供内容，不能指定 JVM/classpath。 */
    public SkillToolProvider(KnowledgeUseCases knowledge, SandboxPolicy ceiling, SkillScriptGateway scripts) {
        this.knowledge = Objects.requireNonNull(knowledge);
        this.ceiling = Objects.requireNonNull(ceiling);
        this.scripts = scripts;
    }

    @Override
    public String id() {
        return "skills";
    }

    @Override
    public List<ToolDescriptor> catalog() {
        return scripts == null ? List.of(READ, RESOURCE) : List.of(READ, RESOURCE, SCRIPT);
    }

    @Override
    public List<RegisteredTool> tools(TurnExecutionContext turn) {
        Map<String, KnowledgeRepository.SkillEntry> available = knowledge.listSkills().stream()
                .filter(KnowledgeRepository.SkillEntry::enabled)
                .collect(Collectors.toUnmodifiableMap(KnowledgeRepository.SkillEntry::id, Function.identity()));
        var tools = new ArrayList<RegisteredTool>();
        tools.add(new RegisteredTool(READ, ToolOrigin.BUILTIN, ToolRisk.LOW, false, ceiling, context -> {
                    var selected = selected(available, context);
                    String instructions = SkillManifests.instructions(selected.manifest());
                    var resources = SkillManifests.resources(selected.manifest());
                    String catalog = resources.stream()
                            .map(value -> value.path() + (value.executable() ? " [script]" : " [reference]"))
                            .collect(Collectors.joining("\n"));
                    String content =
                            instructions + (catalog.isBlank() ? "" : "\n资源目录（按需使用 skill_resource_read）：\n" + catalog);
                    requireBounded(content);
                    context.events()
                            .append(new ThreadItem.ContextUsage(
                                    "skill",
                                    selected.id(),
                                    selected.revision(),
                                    "完整指令已读取；sha256=" + PromptHashes.sha256(instructions)));
                    return result("skill_read", selected, content);
                })
                .readOnly());
        tools.add(new RegisteredTool(RESOURCE, ToolOrigin.BUILTIN, ToolRisk.LOW, false, ceiling, context -> {
                    var selected = selected(available, context);
                    var resource = knowledge.readSkillResource(
                            selected.id(),
                            selected.revision(),
                            context.arguments().path("path").asText());
                    requireBounded(resource.content());
                    context.events()
                            .append(new ThreadItem.ContextUsage(
                                    "skill-resource",
                                    selected.id() + "/" + resource.path(),
                                    selected.revision(),
                                    "sha256=" + PromptHashes.sha256(resource.content())));
                    return result("skill_resource_read", selected, resource.content());
                })
                .readOnly());
        if (scripts != null) {
            tools.add(new RegisteredTool(SCRIPT, ToolOrigin.BUILTIN, ToolRisk.HIGH, false, ceiling, context -> {
                var selected = selected(available, context);
                var resource = knowledge.readSkillResource(
                        selected.id(),
                        selected.revision(),
                        context.arguments().path("path").asText());
                var result = scripts.execute(resource, context.call(), context.sandboxPolicy());
                return new ToolHandler.Result(result.item(), result.modelContent());
            }));
        }
        return List.copyOf(tools);
    }

    private KnowledgeRepository.SkillEntry selected(
            Map<String, KnowledgeRepository.SkillEntry> available, ToolHandler.Context context) {
        String id = context.arguments().path("skillId").asText();
        long revision = context.arguments().path("revision").asLong();
        var selected = available.get(id);
        if (selected == null || selected.revision() != revision) {
            throw new IllegalArgumentException("Skill is not in this Turn snapshot");
        }
        var current = knowledge.readSkill(id);
        if (!current.enabled() || current.revision() != revision) {
            throw new IllegalStateException("Skill was disabled or changed");
        }
        return selected;
    }

    private static ToolHandler.Result result(String tool, KnowledgeRepository.SkillEntry selected, String content) {
        return new ToolHandler.Result(
                new ThreadItem.DynamicToolCall(
                        tool,
                        Map.of(
                                "skillId",
                                selected.id(),
                                "revision",
                                Long.toString(selected.revision()),
                                "status",
                                "completed")),
                content);
    }

    private static void requireBounded(String content) {
        // 不让输出裁剪静默截掉 Skill 末尾的关键限制；超长内容需要用户分拆而不是执行半份指令。
        if (content.length() > 24_000) {
            throw new IllegalArgumentException("Skill content exceeds context budget; split resources explicitly");
        }
    }
}
