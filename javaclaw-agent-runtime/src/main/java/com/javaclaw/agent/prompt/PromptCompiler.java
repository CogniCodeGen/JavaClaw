package com.javaclaw.agent.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnConfig;

/** 纯函数式提示词编译器；不读取数据库、文件规则、凭据或 Provider，不通过字符串尾部拼接模拟权限。 */
public final class PromptCompiler {
    private static final int MAX_INPUT_CHARACTERS = 256_000;
    private final PromptCatalog catalog;

    /** 使用已经完成内容校验的内置模板目录。 */
    public PromptCompiler(PromptCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** 编译本次调用的有序消息及版本快照。参考资料一律使用 USER 数据消息；外部采样不继承人设和 AGENTS.md。 超过输入上限直接拒绝，不静默截断 Skill 或约束。tools 必须来自本 Turn 的真实快照。 */
    public CompiledPrompt compile(
            PromptPurpose purpose,
            TurnConfig config,
            List<ToolDescriptor> tools,
            List<ContextBlock> context,
            AgentsInstructionResolution instructions,
            List<ModelMessage> dialogue) {
        boolean isolated = purpose == PromptPurpose.MCP_SAMPLING;
        if ((isolated || purpose == PromptPurpose.PROMPT_OPTIMIZATION) && !tools.isEmpty()) {
            throw new IllegalArgumentException("isolated model tasks cannot expose tools");
        }
        List<PromptTemplate> templates = new ArrayList<>();
        templates.add(catalog.require("base"));
        if (!isolated) {
            templates.add(catalog.require("coding"));
        }
        templates.add(catalog.require(template(purpose)));
        PromptTemplate summaryPrefix = catalog.require("summary_prefix");
        if (dialogue.stream().anyMatch(message -> message.content().startsWith(summaryPrefix.content()))) {
            templates.add(summaryPrefix);
        }
        if (tools.stream().anyMatch(value -> value.name().equals("skill_read"))) {
            templates.add(catalog.require("skill_usage"));
        }
        // 领域片段只在本次确有对应能力或任务时加入；模板说明不赋予工具权限，也不额外触发模型调用。
        if (purpose != PromptPurpose.BROWSER
                && tools.stream().anyMatch(value -> value.name().startsWith("browser_"))) {
            templates.add(catalog.require("browser"));
        }
        if (purpose == PromptPurpose.MEMORY_EXTRACTION) {
            templates.add(catalog.require("memory_consistency"));
        }
        if (purpose == PromptPurpose.LOOP_EXECUTION || purpose == PromptPurpose.SDD) {
            templates.add(catalog.require("evaluation"));
        }
        StringBuilder system = new StringBuilder();
        templates.stream()
                .filter(value -> !value.id().equals("compaction") && !value.id().equals("summary_prefix"))
                .forEach(value -> system.append(value.content()).append('\n'));
        String persona = isolated ? "" : config.attributes().getOrDefault("systemPrompt", "");
        if (!persona.isBlank()) {
            system.append("\n用户可编辑的人设与业务约定（不替换固定底座、不扩大权限）：\n").append(persona).append('\n');
        }
        List<PromptSnapshot.ContextRef> refs = new ArrayList<>();
        // 只列举已治理的稳定名称。外部描述符正文经模型工具接口传递，不能作为基础授权规则。
        List<ToolDescriptor> orderedTools = tools.stream()
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
        system.append("\n本次可用工具（未列出的能力不可用）：")
                .append(
                        orderedTools.isEmpty()
                                ? "无"
                                : String.join(
                                        ", ",
                                        orderedTools.stream()
                                                .map(ToolDescriptor::name)
                                                .toList()))
                .append("\n沙箱模式：")
                .append(config.sandboxPolicy().mode())
                .append("；审批模式：")
                .append(config.approvalPolicy())
                .append("。最终权限由运行时校验。\n");
        LinkedHashMap<String, ContextBlock> unique = new LinkedHashMap<>();
        for (ContextBlock block : context) {
            String key = PromptHashes.sequence(
                    List.of(block.source(), block.id(), Long.toString(block.revision()), block.sha256()));
            unique.putIfAbsent(key, block);
        }
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage(ModelMessage.Role.SYSTEM, system.toString(), null));
        if (!isolated && instructions.hasModelContext()) {
            messages.add(new ModelMessage(ModelMessage.Role.USER, instructions.asModelContext(), null));
            instructions
                    .sources()
                    .forEach(source -> refs.add(new PromptSnapshot.ContextRef(
                            "agents-" + source.scope(),
                            source.path().toString(),
                            0,
                            source.sha256(),
                            source.bytes(),
                            source.truncated())));
        }
        for (ContextBlock block : unique.values()) {
            messages.add(new ModelMessage(ModelMessage.Role.USER, block.asReference(), null));
            refs.add(new PromptSnapshot.ContextRef(block.source(), block.id(), block.revision(), block.sha256()));
        }
        int conversationStartIndex = messages.size();
        for (ModelMessage message : dialogue) {
            if (message.role() == ModelMessage.Role.SYSTEM) {
                throw new IllegalArgumentException("dialogue and retrieved data cannot supply system instructions");
            }
            messages.add(message);
            for (var image : message.images()) {
                refs.add(new PromptSnapshot.ContextRef("attachment", image.sha256(), 0, image.sha256()));
            }
        }
        long size =
                messages.stream().mapToLong(value -> value.content().length()).sum()
                        + orderedTools.stream()
                                .mapToLong(value -> value.description().length()
                                        + value.inputSchemaJson().length())
                                .sum();
        if (size > MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException(
                    "prompt exceeds context limit; compact or select fewer references explicitly");
        }
        List<String> toolFields = new ArrayList<>();
        orderedTools.forEach(
                value -> toolFields.addAll(List.of(value.name(), value.description(), value.inputSchemaJson())));
        List<String> messageFields = new ArrayList<>();
        messages.forEach(value -> {
            messageFields.add(value.role().name());
            messageFields.add(value.content());
            value.images().forEach(image -> messageFields.addAll(List.of(image.sha256(), image.mediaType())));
        });
        return new CompiledPrompt(
                messages,
                new PromptSnapshot(
                        purpose,
                        templates.stream()
                                .map(value ->
                                        new PromptSnapshot.TemplateRef(value.id(), value.version(), value.sha256()))
                                .toList(),
                        isolated ? "" : config.attributes().getOrDefault("profileId", ""),
                        isolated ? 0 : Long.parseLong(config.attributes().getOrDefault("profileRevision", "0")),
                        PromptHashes.sha256(persona),
                        PromptHashes.sequence(toolFields),
                        refs,
                        PromptHashes.sequence(messageFields)),
                conversationStartIndex);
    }

    /** 从已解析 Profile 类型选择交互目的；未知类型失败，不自动降级为可写 CHAT。 */
    public static PromptPurpose forProfile(TurnConfig config) {
        return switch (config.attributes().getOrDefault("profileKind", "CHAT")) {
            case "CHAT" -> PromptPurpose.CHAT;
            case "PLAN" -> PromptPurpose.PLAN;
            case "LOOP" -> PromptPurpose.LOOP_EXECUTION;
            case "WORKFLOW" -> PromptPurpose.WORKFLOW_NODE;
            case "SDD" -> PromptPurpose.SDD;
            case "SCHEDULE" -> PromptPurpose.SCHEDULE;
            case "SUBAGENT" -> PromptPurpose.SUBAGENT;
            default -> throw new IllegalArgumentException("unknown profile kind");
        };
    }

    private static String template(PromptPurpose purpose) {
        return switch (purpose) {
            case CHAT -> "chat";
            case PLAN -> "plan";
            case LOOP_EXECUTION -> "loop";
            case LOOP_EVALUATION -> "evaluation";
            case WORKFLOW_NODE -> "workflow";
            case SDD -> "sdd";
            case SCHEDULE -> "schedule";
            case SUBAGENT -> "subagent";
            case REVIEW -> "review";
            case COMPACTION -> "compaction";
            case MEMORY_EXTRACTION -> "memory";
            case MEMORY_CONSISTENCY -> "memory_consistency";
            case SKILL_EXTRACTION -> "skill";
            case BROWSER -> "browser";
            case OCR -> "ocr";
            case PROMPT_OPTIMIZATION -> "prompt_optimize";
            case MCP_SAMPLING -> "mcp_sampling";
        };
    }

    /**
     * 一次编译的不可变结果。
     *
     * @param messages 非空有序消息列表，包含私有内容，仅交给模型
     * @param snapshot 不含私有正文的审计快照
     * @param conversationStartIndex messages 中真实对话开始的位置；此前策略和资料每次重新编译
     */
    public record CompiledPrompt(List<ModelMessage> messages, PromptSnapshot snapshot, int conversationStartIndex) {
        /** 创建旧调用方使用的完整对话编译结果；首条 SYSTEM 之后均视为动态对话。 */
        public CompiledPrompt(List<ModelMessage> messages, PromptSnapshot snapshot) {
            this(messages, snapshot, 1);
        }

        /** 固定实际发送的消息序列。 */
        public CompiledPrompt {
            messages = List.copyOf(messages);
            if (conversationStartIndex < 1 || conversationStartIndex > messages.size()) {
                throw new IllegalArgumentException("conversationStartIndex is outside compiled messages");
            }
        }
    }
}
