package com.javaclaw.task.sdd.agent;

import com.javaclaw.agent.hook.LoopDetectionHook;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.agent.model.ModelTier;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.prompt.SddPrompts;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.task.TaskTokenHook;
import com.javaclaw.task.ValidationInspectionTools;
import com.javaclaw.task.sdd.SddAgents;
import com.javaclaw.task.sdd.SddTokenSink;
import com.javaclaw.task.sdd.SddWorkNotes;
import com.javaclaw.task.sdd.TaskContext;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import com.javaclaw.task.sdd.spec.Capability;
import com.javaclaw.task.sdd.spec.Criterion;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;
import com.javaclaw.task.sdd.spec.Requirement;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.spec.TaskItem;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonSchemaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * {@link SddAgents} 的 AgentScope 实现 —— 把 SDD 六阶段的模型驱动行为接到 ReActAgent +
 * 结构化输出 + 各 superpowers 子技能提示。
 *
 * <p>编排器（{@code SddOrchestrator}）只依赖 {@link SddAgents} 接口，故本类是唯一与 AgentScope
 * 耦合的实现点。结构化阶段（提案/规格/计划/补做）用 {@code agent.call(msgs, Pojo.class)} 取
 * {@code _structured_output} 再映射为领域记录；实现阶段（executeTask）用带能力工具 + 只读自检工具
 * 的执行体跑出文本摘要，并识别懒拆解请求。</p>
 *
 * <p>所有调用同步阻塞（编排器在后台线程驱动），用 CountDownLatch 收敛 reactive 流。</p>
 *
 * @author JavaClaw
 */
public final class AgentScopeSddAgents implements SddAgents {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeSddAgents.class);

    /** 执行体请求懒拆解的哨兵：摘要中出现此前缀行则按其后子项拆解。 */
    public static final String SPLIT_SENTINEL = "[需要拆解]";

    private final ModelFactory modelFactory;
    private final java.util.Map<String, Object> capabilityTools;
    private final SkillManager skills;
    private final SddTokenSink tokenSink;

    private long structuredTimeoutSec = 120;
    private long execTimeoutSec = 300;
    private int execMaxIters = 12;

    public AgentScopeSddAgents(ModelFactory modelFactory, java.util.Map<String, Object> capabilityTools,
                               SkillManager skills, SddTokenSink tokenSink) {
        this.modelFactory = modelFactory;
        this.capabilityTools = capabilityTools == null ? java.util.Map.of() : capabilityTools;
        this.skills = skills;
        this.tokenSink = tokenSink == null ? SddTokenSink.NOOP : tokenSink;
    }

    /** 把阶段标签绑进 token 钩子需要的 (in,out) 回调。 */
    private BiConsumer<Long, Long> tok(String phase) {
        return (i, o) -> tokenSink.record(phase, i, o);
    }

    /**
     * 为长会话智能体（实现执行体）构建独立的自动上下文压缩记忆，封顶单次调用内 ReAct 累积的
     * 上下文体积——避免 12 轮迭代把工具结果未压缩反复重发（与提供商无关的省 token 手段）。
     * 每个 agent 须用独立实例（记忆有状态）。
     */
    private AutoContextMemory buildMemory() {
        AgentConfig cfg = AgentConfig.getInstance();
        AutoContextConfig mc = AutoContextConfig.builder()
                .maxToken(cfg.getMemoryMaxToken())
                .msgThreshold(cfg.getMemoryMsgThreshold())
                .lastKeep(cfg.getMemoryLastKeep())
                .tokenRatio(cfg.getMemoryTokenRatio())
                .build();
        return new AutoContextMemory(mc, modelFactory.createChatModel());
    }

    public AgentScopeSddAgents structuredTimeoutSec(long s) { this.structuredTimeoutSec = s; return this; }
    public AgentScopeSddAgents execTimeoutSec(long s) { this.execTimeoutSec = s; return this; }
    public AgentScopeSddAgents execMaxIters(int n) { this.execMaxIters = n; return this; }

    // ==================== 阶段 1-2：提案 ====================

    @Override
    public Proposal clarifyAndPropose(TaskContext ctx, String feedback) {
        String sys = withSkills(SddPrompts.PROPOSE_SYS_PROMPT, "需求澄清", "规格驱动开发");
        ReActAgent agent = ReActAgent.builder()
                .name("提案-" + ctx.id())
                .sysPrompt(sys)
                .model(modelFactory.createStructuredChatModel(ModelTier.LIGHT))
                .maxIters(2)
                .hooks(List.of(new TaskTokenHook(tok("proposal"))))
                .enablePendingToolRecovery(true)
                .build();
        String user = "用户需求：\n" + ctx.description()
                + (feedback == null || feedback.isBlank() ? "" : "\n\n上一轮评审驳回意见（请据此修正）：\n" + feedback);
        SddDrafts.ProposalDraft d = structured(agent, user, SddDrafts.ProposalDraft.class);
        if (d == null) {
            return new Proposal(ctx.description(), "（提案产出失败，按原始需求处理）", "");
        }
        return new Proposal(nz(d.why), nz(d.whatChanges), nz(d.outOfScope));
    }

    // ==================== 阶段 3：规格 ====================

    @Override
    public List<Capability> specify(TaskContext ctx, Proposal proposal) {
        String sys = withSkills(SddPrompts.SPECIFY_SYS_PROMPT, "规格驱动开发");
        ReActAgent agent = ReActAgent.builder()
                .name("规格-" + ctx.id())
                .sysPrompt(sys)
                .model(modelFactory.createStructuredChatModel(ModelTier.HIGH))
                .maxIters(2)
                .hooks(List.of(new TaskTokenHook(tok("spec"))))
                .enablePendingToolRecovery(true)
                .build();
        String user = "提案：\n为什么：" + proposal.why() + "\n改什么：" + proposal.whatChanges()
                + "\n不改什么：" + nz(proposal.outOfScope()) + "\n\n用户原始需求：\n" + ctx.description();
        SddDrafts.SpecDraft d = structured(agent, user, SddDrafts.SpecDraft.class);
        if (d == null || d.capabilities == null) return List.of();
        List<Capability> caps = new ArrayList<>();
        for (SddDrafts.CapabilityDraft cd : d.capabilities) {
            if (cd == null || cd.name == null) continue;
            List<Requirement> reqs = new ArrayList<>();
            if (cd.requirements != null) {
                for (SddDrafts.RequirementDraft rd : cd.requirements) {
                    if (rd == null || rd.title == null) continue;
                    List<Scenario> scs = new ArrayList<>();
                    if (rd.scenarios != null) {
                        for (SddDrafts.ScenarioDraft sd : rd.scenarios) {
                            if (sd == null || sd.title == null) continue;
                            scs.add(new Scenario(sd.title, nz(sd.given), nz(sd.when), nz(sd.then),
                                    new Criterion(sd.criterionType, sd.criterionPredicate)));
                        }
                    }
                    reqs.add(new Requirement(rd.title, scs));
                }
            }
            caps.add(new Capability(cd.name, reqs));
        }
        return caps;
    }

    // ==================== 阶段 4：设计（按需） ====================

    @Override
    public String design(TaskContext ctx, Proposal proposal, List<Capability> capabilities) {
        String sys = withSkills(SddPrompts.DESIGN_SYS_PROMPT, "规格驱动开发");
        ReActAgent agent = ReActAgent.builder()
                .name("设计-" + ctx.id())
                .sysPrompt(sys)
                .model(modelFactory.createChatModel())
                .maxIters(3)
                .hooks(List.of(new TaskTokenHook(tok("design"))))
                .enablePendingToolRecovery(true)
                .build();
        String user = "需求：" + ctx.description() + "\n能力数：" + capabilities.size();
        String text = textCall(agent, user, structuredTimeoutSec);
        if (text == null || text.isBlank() || text.contains("无需设计")) return null;
        return text;
    }

    // ==================== 阶段 5：任务拆解 ====================

    @Override
    public List<TaskItem> planTasks(TaskContext ctx, Proposal proposal, List<Capability> capabilities,
                                    String design, String feedback) {
        String sys = withSkills(SddPrompts.PLAN_TASKS_SYS_PROMPT, "规格驱动开发");
        ReActAgent agent = ReActAgent.builder()
                .name("计划-" + ctx.id())
                .sysPrompt(sys)
                .model(modelFactory.createStructuredChatModel(ModelTier.HIGH))
                .maxIters(2)
                .hooks(List.of(new TaskTokenHook(tok("plan"))))
                .enablePendingToolRecovery(true)
                .build();
        String user = "提案改什么：" + proposal.whatChanges()
                + "\n能力规格场景数：" + capabilities.stream().mapToInt(c -> c.allScenarios().size()).sum()
                + (design == null ? "" : "\n设计要点：见 design.md")
                + (feedback == null || feedback.isBlank() ? "" : "\n\n需据以下反馈调整/补做：\n" + feedback)
                + "\n\n工作目录：" + ctx.workDir();
        SddDrafts.TaskPlanDraft d = structured(agent, user, SddDrafts.TaskPlanDraft.class);
        if (d == null || d.tasks == null) return List.of();
        List<TaskItem> items = new ArrayList<>();
        int idx = 1;
        for (SddDrafts.TaskItemDraft td : d.tasks) {
            if (td == null || td.action == null) continue;
            if (isMetaTask(td)) {
                log.warn("[SDD] 任务 {} 计划项被过滤（重做规格文档的元任务）：{}", ctx.id(), td.action);
                continue;
            }
            items.add(new TaskItem(idx++, td.action,
                    td.files == null ? List.of() : td.files, td.criterion, false));
        }
        return items;
    }

    /**
     * 识别"重做 SDD 脚手架"的元任务：proposal/spec/design/tasks 文档由编排器各阶段产出，
     * 用户评审由 ReviewGate 负责，这类项混进实现清单会让执行体重写一遍规格目录并触发假验收。
     */
    private static boolean isMetaTask(SddDrafts.TaskItemDraft td) {
        StringBuilder sb = new StringBuilder(td.action);
        if (td.files != null) td.files.forEach(f -> sb.append(' ').append(f));
        if (td.criterion != null) sb.append(' ').append(td.criterion);
        return isMetaTaskText(sb.toString());
    }

    /** 字符串版元任务判定：供 remediate 的补做项（仅动作文本）复用 */
    private static boolean isMetaTaskText(String text) {
        if (text.contains(".agent/openspec") || text.contains("openspec/changes")) return true;
        if (text.contains("proposal.md") || text.contains("design.md")
                || text.contains("tasks.md") || text.contains("spec.md")) return true;
        // 「向用户展示方案/获取书面确认」类流程项：评审闸门已覆盖，执行体无法完成。
        // 只匹配明确的流程短语，避免误杀「实现确认对话框」等正经 UI 任务
        return text.contains("向用户展示") || text.contains("获取用户确认") || text.contains("等待用户确认")
                || text.contains("征得用户") || text.contains("用户书面") || text.contains("获取书面确认");
    }

    // ==================== 阶段 6：实现 ====================

    @Override
    public ExecutionResult executeTask(TaskContext ctx, TaskItem current, List<TaskItem> doneItems,
                                       List<Capability> specs) {
        Toolkit toolkit = buildToolkit(ctx);
        String sys = withSkills(SddPrompts.executeTaskSysPrompt(SPLIT_SENTINEL));
        sys = withSkillsCompact(sys, "测试驱动开发", "系统化调试", "代码评审");
        ReActAgent agent = ReActAgent.builder()
                .name("实现-" + ctx.id() + "-" + current.index())
                .sysPrompt(sys)
                .model(modelFactory.createChatModel())
                .maxIters(execMaxIters)
                .toolkit(toolkit)
                .memory(buildMemory())
                .hooks(List.of(new LoopDetectionHook(), new TaskTokenHook(tok("implement"))))
                .enablePendingToolRecovery(true)
                .build();

        StringBuilder user = new StringBuilder();
        user.append("当前实现项 #").append(current.index()).append("：").append(current.action()).append("\n");
        if (current.files() != null && !current.files().isEmpty()) {
            user.append("涉及文件：").append(String.join(", ", current.files())).append("\n");
        }
        if (current.criterion() != null && !current.criterion().isBlank()) {
            user.append("完成判据：").append(current.criterion()).append("\n");
        }
        user.append("工作目录：").append(ctx.workDir()).append("\n");
        // 进度账本（盘上跨项记忆）：替代把前项完整 transcript 带进上下文。
        // 只带最近 10 行——账本随 ReAct 每轮迭代全量重发，行数直接放大固定载荷；更早的历史在盘上，需要时可 inspect_read
        String ledger = SddWorkNotes.readLedgerTail(ctx.workDir(), 10);
        if (!ledger.isBlank()) {
            user.append("\n【已完成项进度账本（最近，盘上 .agent/progress.md）】\n").append(ledger).append("\n");
        }
        // 项目地图（确定性、零模型）：免每项重跑 find 重新发现项目
        String pmap = SddWorkNotes.ensureProjectMap(ctx.workDir());
        if (!pmap.isBlank()) {
            user.append("\n【项目文件清单（已为你列好，勿再 find 全树；要看内容用 inspect_read，可带 from_line/to_line）】\n")
                    .append(pmap).append("\n");
        }

        String text = textCall(agent, user.toString(), execTimeoutSec);
        if (text == null) text = "";
        List<String> split = parseSplit(text);
        if (!split.isEmpty()) return ExecutionResult.split(split);
        // 完成项：把本项动作 + 单行摘要追加到进度账本，供后续项复用
        SddWorkNotes.appendLedger(ctx.workDir(),
                "#" + current.index() + " " + current.action() + " — " + SddWorkNotes.oneLine(text, 120));
        return ExecutionResult.done(text.isBlank() ? "（执行体未输出摘要）" : text);
    }

    // ==================== 验收补做 ====================

    @Override
    public List<String> remediate(TaskContext ctx, List<Scenario> failedScenarios, OpenSpecChange change) {
        String sys = withSkills(SddPrompts.REMEDIATE_SYS_PROMPT, "系统化调试");
        ReActAgent agent = ReActAgent.builder()
                .name("补做-" + ctx.id())
                .sysPrompt(sys)
                .model(modelFactory.createStructuredChatModel(ModelTier.HIGH))
                .maxIters(2)
                .hooks(List.of(new TaskTokenHook(tok("remediate"))))
                .enablePendingToolRecovery(true)
                .build();
        String fails = failedScenarios.stream()
                .map(s -> "- " + s.title() + "（判据：" + s.criterion() + "）")
                .collect(Collectors.joining("\n"));
        String user = "以下验收场景未通过，请给出补做项：\n" + fails + "\n\n工作目录：" + ctx.workDir();
        SddDrafts.RemediationDraft d = structured(agent, user, SddDrafts.RemediationDraft.class);
        if (d == null || d.fixes == null) return List.of();
        return d.fixes.stream()
                .filter(s -> s != null && !s.isBlank())
                .filter(s -> {
                    if (isMetaTaskText(s)) {
                        log.warn("[SDD] 任务 {} 补做项被过滤（重做规格文档的元任务）：{}", ctx.id(), s);
                        return false;
                    }
                    return true;
                })
                .toList();
    }

    // ==================== AgentScope 调用助手 ====================

    private Toolkit buildToolkit(TaskContext ctx) {
        Toolkit toolkit = new Toolkit();
        String caps = ctx.capabilities() == null ? "" : ctx.capabilities().toLowerCase();
        boolean all = caps.isBlank() || caps.contains("auto");
        for (var e : capabilityTools.entrySet()) {
            if (e.getValue() == null) continue;
            if (all || caps.contains(e.getKey().toLowerCase())) {
                toolkit.registerTool(e.getValue());
            }
        }
        // 只读自检工具：执行体据此核实自己的产出
        if (ctx.workDir() != null && !ctx.workDir().isBlank()) {
            toolkit.registerTool(new ValidationInspectionTools(ctx.workDir()));
        }
        // 技能按需拉取工具：配合 withSkillsCompact 的目录式注入，让执行体只在需要时拉技能全文
        toolkit.registerTool(new com.javaclaw.skill.SkillTools());
        // 瘦身：GUI 自动化类工具与代码实现无关，但 schema 随 ReAct 每轮迭代全量重发——
        // 留着每轮白付 ~1.5K token。需要 GUI 自动化的诉求走聊天模式 system_expert，不进托管执行体。
        for (String t : EXEC_IRRELEVANT_TOOLS) {
            if (toolkit.getToolNames().contains(t)) toolkit.removeTool(t);
        }
        return toolkit;
    }

    /** 托管执行体用不到的 GUI 自动化/环境查询工具（schema 每轮重发，移除以压固定载荷） */
    private static final Set<String> EXEC_IRRELEVANT_TOOLS = Set.of(
            "sys_screenshot", "sys_mouse_move", "sys_mouse_click", "sys_mouse_click_at",
            "sys_mouse_scroll", "sys_key_type", "sys_key_press", "sys_key_combo",
            "sys_get_info", "sys_get_time");

    /** 结构化调用：同步阻塞取 _structured_output 并转 POJO。 */
    private <T> T structured(ReActAgent agent, String userPrompt, Class<T> cls) {
        Msg result = blockingCall(agent, userPrompt, cls, structuredTimeoutSec);
        return extractStructuredOutput(result, cls);
    }

    /** 文本调用：同步阻塞取最终文本。 */
    private String textCall(ReActAgent agent, String userPrompt, long timeoutSec) {
        Msg result = blockingCall(agent, userPrompt, null, timeoutSec);
        return extractTextResult(result);
    }

    /** 统一的阻塞订阅：cls 非空走结构化 call，否则走普通 call。失败/超时抛 RuntimeException。 */
    private Msg blockingCall(ReActAgent agent, String userPrompt, Class<?> cls, long timeoutSec) {
        Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(userPrompt).build();
        AtomicReference<Msg> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        var flux = (cls == null) ? agent.call(List.of(userMsg)) : agent.call(List.of(userMsg), cls);
        Disposable d = flux.subscribeOn(Schedulers.boundedElastic())
                .subscribe(ref::set, e -> { err.set(e); latch.countDown(); }, latch::countDown);
        try {
            if (!latch.await(Math.max(1, timeoutSec), TimeUnit.SECONDS)) {
                d.dispose();
                throw new RuntimeException("智能体调用超时（" + timeoutSec + "s）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            d.dispose();
            throw new RuntimeException("智能体调用被中断", e);
        }
        if (err.get() != null) {
            Throwable t = err.get();
            throw (t instanceof RuntimeException re) ? re : new RuntimeException(t);
        }
        return ref.get();
    }

    private static <T> T extractStructuredOutput(Msg msg, Class<T> cls) {
        if (msg == null || msg.getMetadata() == null) {
            log.warn("[SDD] 结构化输出缺失（{}）", cls.getSimpleName());
            return null;
        }
        Object raw = msg.getMetadata().get("_structured_output");
        if (raw == null) {
            log.warn("[SDD] metadata 无 _structured_output（{}）", cls.getSimpleName());
            return null;
        }
        try {
            return JsonSchemaUtils.convertToObject(raw, cls);
        } catch (Exception e) {
            log.warn("[SDD] 结构化输出解析失败（{}）：{}", cls.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static String extractTextResult(Msg msg) {
        if (msg == null) return "";
        List<TextBlock> blocks = msg.getContentBlocks(TextBlock.class);
        if (blocks.isEmpty()) return "";
        return blocks.stream().map(TextBlock::getText).filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    /** 从执行摘要里识别懒拆解请求行，返回子项动作（无则空列表）。 */
    static List<String> parseSplit(String text) {
        if (text == null) return List.of();
        for (String line : text.split("\n")) {
            String t = line.strip();
            int p = t.indexOf(SPLIT_SENTINEL);
            if (p >= 0) {
                String rest = t.substring(p + SPLIT_SENTINEL.length()).replaceFirst("^[：:\\s]+", "");
                List<String> parts = new ArrayList<>();
                for (String s : rest.split("[；;]")) {
                    if (!s.isBlank()) parts.add(s.strip());
                }
                if (!parts.isEmpty()) return parts;
            }
        }
        return List.of();
    }

    private String withSkills(String base, String... skillNames) {
        if (skills == null || skillNames == null || skillNames.length == 0) return base;
        StringBuilder sb = new StringBuilder(base);
        for (String name : skillNames) {
            try {
                String detail = skills.buildSkillDetail(name);
                if (detail != null && !detail.isBlank()) {
                    sb.append("\n\n").append(detail);
                }
            } catch (Exception ignore) {
                // 技能缺失不致命
            }
        }
        return sb.toString();
    }

    /**
     * 技能的"目录式"注入（渐进加载）：只贴技能名 + 一行描述 + skill_read 提示，不内联全文。
     * 用于带 toolkit 的执行体（可经 skill_read 按需拉全文），避免把多篇技能正文每轮每项全价重发。
     * 结构化阶段（无 toolkit、调用次数有限）仍用 {@link #withSkills} 内联全文。
     */
    private String withSkillsCompact(String base, String... skillNames) {
        if (skills == null || skillNames == null || skillNames.length == 0) return base;
        StringBuilder cat = new StringBuilder();
        for (String name : skillNames) {
            try {
                var sk = skills.getSkillByName(name);
                if (sk != null) {
                    cat.append("\n- ").append(sk.getName());
                    String desc = sk.getDescription();
                    if (desc != null && !desc.isBlank()) cat.append("：").append(desc.strip());
                }
            } catch (Exception ignore) {
                // 技能缺失不致命
            }
        }
        if (cat.length() == 0) return base;
        return base + "\n\n【可用技能（细则按需拉取，勿凭空臆测）】" + cat
                + "\n需要某技能的完整步骤时，用 skill_read(skill_name) 拉取后再执行。";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
