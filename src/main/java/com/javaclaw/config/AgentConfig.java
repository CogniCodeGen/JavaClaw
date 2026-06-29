package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 智能体配置管理器（持久化到本地文件）
 *
 * <p>负责读取和保存智能体相关配置项，配置文件保存在程序运行目录下的 {@code javaclaw-agent.properties}。
 * 采用单例模式，全局共享同一份配置。</p>
 *
 * <p>系统提示词等不常修改的内容保留为类常量，
 * API 连接、模型参数、超时等运行时可调配置项存储在配置文件中。</p>
 *
 * @author JavaClaw
 */
public final class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    /** 配置文件名 */
    private static final String CONFIG_FILE_NAME = "javaclaw-agent.properties";

    /** 单例实例 */
    private static AgentConfig INSTANCE;

    private Path configFilePath;
    private final Properties properties;

    // ==================== 配置项 key ====================

    // 高性能模型（HIGH tier）— 沿用 api.* 前缀（向后兼容），用于编排器、规划、质疑、PlanEvolver 等
    private static final String KEY_PROVIDER_TYPE = "api.provider.type";
    private static final String KEY_BASE_URL = "api.base.url";
    private static final String KEY_MODEL_NAME = "api.model.name";
    private static final String KEY_API_KEY = "api.key";
    private static final String KEY_THINKING_BUDGET = "model.thinking.budget";

    // 普通模型（NORMAL tier）— 用于子专家、单步执行体、知识专家等常规任务
    private static final String KEY_NORMAL_PROVIDER_TYPE = "api.normal.provider.type";
    private static final String KEY_NORMAL_BASE_URL = "api.normal.base.url";
    private static final String KEY_NORMAL_MODEL_NAME = "api.normal.model.name";
    private static final String KEY_NORMAL_API_KEY = "api.normal.key";
    private static final String KEY_NORMAL_THINKING_ENABLED = "api.normal.thinking.enabled";

    // 轻量模型（LIGHT tier）— 用于意图识别、工具路由、记忆蒸馏、视觉预处理、过程评估等
    private static final String KEY_LIGHT_PROVIDER_TYPE = "api.light.provider.type";
    private static final String KEY_LIGHT_BASE_URL = "api.light.base.url";
    private static final String KEY_LIGHT_MODEL_NAME = "api.light.model.name";
    private static final String KEY_LIGHT_API_KEY = "api.light.key";
    private static final String KEY_LIGHT_THINKING_ENABLED = "api.light.thinking.enabled";
    private static final String KEY_CONNECT_TIMEOUT = "timeout.connect.seconds";
    private static final String KEY_READ_TIMEOUT = "timeout.read.seconds";
    private static final String KEY_WRITE_TIMEOUT = "timeout.write.seconds";
    private static final String KEY_MODEL_REQUEST_TIMEOUT = "timeout.model.request.seconds";
    private static final String KEY_ORCHESTRATOR_MAX_ITERS = "orchestrator.max.iters";
    private static final String KEY_WEB_AGENT_MAX_ITERS = "web.agent.max.iters";
    private static final String KEY_EMAIL_AGENT_MAX_ITERS = "email.agent.max.iters";
    private static final String KEY_SYSTEM_AGENT_MAX_ITERS = "system.agent.max.iters";
    private static final String KEY_NOTIFICATION_AGENT_MAX_ITERS = "notification.agent.max.iters";
    private static final String KEY_HTTP_VERSION = "http.version";
    private static final String KEY_THINKING_ENABLED = "model.thinking.enabled";
    private static final String KEY_MAX_REPEATED_TOOL_CALLS = "loop.max.repeated.calls";
    private static final String KEY_LOOP_SIMILARITY_THRESHOLD = "loop.similarity.threshold";
    private static final String KEY_EVALUATOR_PASS_THRESHOLD = "evaluator.pass.threshold";
    private static final String KEY_EVALUATOR_MAX_RETRIES = "evaluator.max.retries";
    private static final String KEY_MEMORY_MAX_TOKEN = "memory.max.token";
    private static final String KEY_MEMORY_MSG_THRESHOLD = "memory.msg.threshold";
    private static final String KEY_MEMORY_LAST_KEEP = "memory.last.keep";
    private static final String KEY_MEMORY_TOKEN_RATIO = "memory.token.ratio";
    private static final String KEY_RETRY_MAX_ATTEMPTS = "retry.max.attempts";
    private static final String KEY_RETRY_INITIAL_BACKOFF = "retry.initial.backoff.seconds";
    private static final String KEY_RETRY_MAX_BACKOFF = "retry.max.backoff.seconds";
    private static final String KEY_PLAN_MODE_MAX_ROUNDS = "plan.mode.max.rounds";
    private static final String KEY_PLAN_MODE_MAX_EXPERTS = "plan.mode.max.experts";
    private static final String KEY_FIRST_USE_GUIDANCE_DONE = "ui.first.use.guidance.done";
    private static final String KEY_TRAY_MINIMIZE_ON_CLOSE = "ui.tray.minimize.on.close";
    private static final String KEY_UI_THEME = "ui.theme";
    private static final String KEY_CONFIRMATION_TIMEOUT_DEFAULT = "confirmation.timeout.default.seconds";
    private static final String KEY_CONFIRMATION_TIMEOUT_MANAGED = "confirmation.timeout.managed.seconds";
    private static final String KEY_TASK_EVENTS_RETENTION_DAYS = "task.events.retention.days";
    private static final String KEY_SCHEDULE_THREAD_POOL_SIZE = "schedule.thread.pool.size";
    private static final String KEY_TASK_VERIFICATION_ENABLED = "task.verification.enabled";
    private static final String KEY_TOOL_ROUTING_ENABLED = "tool.routing.enabled";

    // 任务管理配置
    private static final String KEY_TASK_AGENT_MAX_ITERS = "task.agent.max.iters";
    private static final String KEY_TASK_MAX_CONCURRENT = "task.max.concurrent";
    private static final String KEY_COMMAND_AGENT_MAX_ITERS = "command.agent.max.iters";

    // 任务各阶段 .block() 超时（秒）；思考模式下 30s 常常不够，默认放宽
    private static final String KEY_TASK_SUBTASK_EXECUTOR_TIMEOUT = "task.subtask.executor.timeout.seconds";

    // SDD 托管任务配置：实现项执行/结构化阶段（提案、规格、计划、补做）的整体阻塞超时与执行体迭代上限。
    // 注意超时覆盖的是单次 executeTask 全程（最多 exec.max.iters 轮 ReAct）——慢模型生成大文件单轮即可达数分钟，宁宽勿紧
    private static final String KEY_SDD_EXEC_TIMEOUT = "task.sdd.exec.timeout.seconds";
    private static final String KEY_SDD_STRUCTURED_TIMEOUT = "task.sdd.structured.timeout.seconds";
    private static final String KEY_SDD_EXEC_MAX_ITERS = "task.sdd.exec.max.iters";
    // 托管任务高风险工具"目录内自动放行"：由风险评估智能体判定工具影响范围是否限于任务目录，是则免人工确认
    private static final String KEY_TASK_RISK_AUTOAPPROVE = "task.risk.autoapprove.enabled";

    // GEPA 配置
    private static final String KEY_GEPA_GOAL_ENABLED = "gepa.goal.auto.decompose";
    private static final String KEY_GEPA_EVAL_INTERVAL = "gepa.eval.interval.tasks";
    private static final String KEY_GEPA_EVAL_THRESHOLD = "gepa.eval.threshold";
    private static final String KEY_GEPA_PLAN_ADAPTIVE = "gepa.plan.adaptive.enabled";
    private static final String KEY_GEPA_FEEDBACK_MAX_ROUNDS = "gepa.feedback.loop.max.rounds";
    private static final String KEY_SUBTASK_TOOL_ERROR_MAX = "task.subtask.tool.error.max";

    // JShell 执行配置
    private static final String KEY_JSHELL_EXEC_TIMEOUT = "jshell.exec.timeout.seconds";

    // 技能进化配置（自学习闭环：skill_manage 工具 + SkillCurator 蒸馏）
    private static final String KEY_SKILL_EVOLUTION_MODE = "skill.evolution.mode";
    private static final String KEY_SKILL_EVOLUTION_MIN_TOOLS = "skill.evolution.min.tools";
    private static final String KEY_SKILL_EVOLUTION_SUCCESS_THRESHOLD = "skill.evolution.success.threshold";
    private static final String KEY_SKILL_CURATION_COOLDOWN_DAYS = "skill.curation.cooldown.days";
    private static final String KEY_SKILL_CURATION_DEDUP_HOURS = "skill.curation.dedup.hours";
    private static final String KEY_SKILL_USAGE_LOWSUCCESS_THRESHOLD = "skill.usage.lowsuccess.threshold";
    private static final String KEY_SKILL_USAGE_LOWSUCCESS_MINSAMPLES = "skill.usage.lowsuccess.minsamples";
    private static final String KEY_SKILL_NUDGE_ENABLED = "skill.nudge.enabled";
    private static final String KEY_SKILL_BUNDLES_ENABLED = "skill.bundles.enabled";

    // RAG 知识库配置
    private static final String KEY_RAG_ENABLED = "rag.enabled";
    private static final String KEY_RAG_EMBEDDING_PROVIDER = "rag.embedding.provider";
    private static final String KEY_RAG_EMBEDDING_BASE_URL = "rag.embedding.base.url";
    private static final String KEY_RAG_EMBEDDING_API_KEY = "rag.embedding.api.key";
    private static final String KEY_RAG_EMBEDDING_MODEL_NAME = "rag.embedding.model.name";
    private static final String KEY_RAG_EMBEDDING_DIMENSIONS = "rag.embedding.dimensions";
    private static final String KEY_RAG_CHUNK_SIZE = "rag.chunk.size";
    private static final String KEY_RAG_CHUNK_OVERLAP = "rag.chunk.overlap";
    private static final String KEY_RAG_RETRIEVE_LIMIT = "rag.retrieve.limit";
    private static final String KEY_RAG_SCORE_THRESHOLD = "rag.score.threshold";

    // ==================== 默认值常量 ====================

    private static final String DEFAULT_PROVIDER_TYPE = "OpenAI";
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:1234";
    private static final String DEFAULT_MODEL_NAME = "qwen/qwen3.5-9b";
    private static final String DEFAULT_API_KEY = "not-needed";
    private static final int DEFAULT_THINKING_BUDGET = 4096;
    private static final int DEFAULT_CONNECT_TIMEOUT = 30;
    private static final int DEFAULT_READ_TIMEOUT = 120;
    private static final int DEFAULT_WRITE_TIMEOUT = 30;
    // 单次模型请求总超时（秒）。覆盖 AgentScope MODEL_DEFAULTS 的 5 分钟默认，
    // 避免 SINGLE 通道一次性生成大段代码时被 PT5M 中断。默认 20 分钟。
    private static final int DEFAULT_MODEL_REQUEST_TIMEOUT = 1200;
    private static final int DEFAULT_ORCHESTRATOR_MAX_ITERS = 10;
    private static final int DEFAULT_WEB_AGENT_MAX_ITERS = 8;
    private static final int DEFAULT_EMAIL_AGENT_MAX_ITERS = 5;
    private static final int DEFAULT_SYSTEM_AGENT_MAX_ITERS = 8;
    private static final int DEFAULT_NOTIFICATION_AGENT_MAX_ITERS = 5;
    private static final boolean DEFAULT_THINKING_ENABLED = true;
    private static final int DEFAULT_MAX_REPEATED_TOOL_CALLS = 8;
    private static final String DEFAULT_HTTP_VERSION = "HTTP_1_1";
    private static final double DEFAULT_LOOP_SIMILARITY_THRESHOLD = 0.8;
    private static final double DEFAULT_EVALUATOR_PASS_THRESHOLD = 3.5;
    private static final int DEFAULT_EVALUATOR_MAX_RETRIES = 2;
    private static final long DEFAULT_MEMORY_MAX_TOKEN = 128 * 1024;
    private static final int DEFAULT_MEMORY_MSG_THRESHOLD = 100;
    private static final int DEFAULT_MEMORY_LAST_KEEP = 50;
    private static final double DEFAULT_MEMORY_TOKEN_RATIO = 0.75;
    private static final int DEFAULT_RETRY_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_RETRY_INITIAL_BACKOFF = 2;
    private static final int DEFAULT_RETRY_MAX_BACKOFF = 30;
    private static final int DEFAULT_PLAN_MODE_MAX_ROUNDS = 3;
    private static final int DEFAULT_PLAN_MODE_MAX_EXPERTS = 4;
    private static final int DEFAULT_CONFIRMATION_TIMEOUT_DEFAULT = 60;
    private static final int DEFAULT_CONFIRMATION_TIMEOUT_MANAGED = 600;
    private static final int DEFAULT_TASK_EVENTS_RETENTION_DAYS = 30;
    private static final int DEFAULT_SCHEDULE_THREAD_POOL_SIZE = 4;
    private static final int DEFAULT_TASK_AGENT_MAX_ITERS = 15;
    private static final int DEFAULT_TASK_MAX_CONCURRENT = 3;
    private static final int DEFAULT_COMMAND_AGENT_MAX_ITERS = 8;
    // 各阶段默认超时；较旧的 30s 经常因为 thinking 模式而超时，改为更宽松的默认
    private static final int DEFAULT_TASK_SUBTASK_EXECUTOR_TIMEOUT = 600;
    // SDD 默认值：单个实现项 15 分钟（慢模型单轮生成大文件可达 3 分钟+，旧 300s 实测不够）、
    // 结构化阶段 5 分钟（旧 120s 对思考模式偏紧）、执行体 12 轮迭代
    private static final int DEFAULT_SDD_EXEC_TIMEOUT = 900;
    private static final int DEFAULT_SDD_STRUCTURED_TIMEOUT = 300;
    private static final int DEFAULT_SDD_EXEC_MAX_ITERS = 12;
    // 默认开启：托管任务中影响范围限于任务目录的高风险操作由风险评估智能体自动放行
    private static final boolean DEFAULT_TASK_RISK_AUTOAPPROVE = true;

    // GEPA 默认值
    private static final boolean DEFAULT_GEPA_GOAL_ENABLED = true;
    private static final int DEFAULT_GEPA_EVAL_INTERVAL = 3;
    private static final double DEFAULT_GEPA_EVAL_THRESHOLD = 3.5;
    private static final boolean DEFAULT_GEPA_PLAN_ADAPTIVE = true;
    private static final int DEFAULT_GEPA_FEEDBACK_MAX_ROUNDS = 2;
    // 子任务连续工具错误的重试上限（达到即终止该子任务）。代码任务"改→编译→再改"
    // 循环常见，默认放宽到 5 次。
    private static final int DEFAULT_SUBTASK_TOOL_ERROR_MAX = 5;

    // RAG 默认值
    private static final boolean DEFAULT_RAG_ENABLED = false;
    private static final String DEFAULT_RAG_EMBEDDING_PROVIDER = "OpenAI";
    private static final String DEFAULT_RAG_EMBEDDING_BASE_URL = "";
    private static final String DEFAULT_RAG_EMBEDDING_API_KEY = "";
    private static final String DEFAULT_RAG_EMBEDDING_MODEL_NAME = "text-embedding-3-small";
    private static final int DEFAULT_RAG_EMBEDDING_DIMENSIONS = 1024;
    private static final int DEFAULT_RAG_CHUNK_SIZE = 512;
    private static final int DEFAULT_RAG_CHUNK_OVERLAP = 50;
    private static final int DEFAULT_RAG_RETRIEVE_LIMIT = 5;
    private static final double DEFAULT_RAG_SCORE_THRESHOLD = 0.3;

    // ==================== 系统提示词（类常量，不存配置文件） ====================

    /** 智能体名称 */
    public static final String AGENT_NAME = "JavaClaw助手";

    /** 编程专家智能体名称 */
    public static final String CODING_AGENT_NAME = "编程专家";

    /** 编程专家智能体描述 */
    public static final String CODING_AGENT_DESCRIPTION =
            "编程专家，擅长代码编写、代码审查、Bug 分析、架构设计、算法讲解等编程相关任务";

    /** 编程专家系统提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#CODING_AGENT_SYS_PROMPT} */

    /** 知识专家智能体名称 */
    public static final String KNOWLEDGE_AGENT_NAME = "知识专家";

    /** 知识专家智能体描述 */
    public static final String KNOWLEDGE_AGENT_DESCRIPTION =
            "知识问答与知识库管理。处理编程以外的知识问答（概念分析、方案对比、学习建议），" +
            "以及知识库操作（导入TXT/PDF文档、语义检索、删除文档）。不处理代码编写。";

    /** 知识专家系统提示词（含 RAG 变体）已迁移至 {@link com.javaclaw.prompt.AgentPrompts} */

    /** Web 浏览专家智能体名称 */
    public static final String WEB_AGENT_NAME = "Web浏览专家";

    /** Web 浏览专家智能体描述 */
    public static final String WEB_AGENT_DESCRIPTION =
            "网页浏览与操作。使用 Playwright 浏览器访问网页、搜索信息、填写表单、" +
            "点击按钮、截图、管理多Tab和Cookie。不处理本地文件或系统操作。";

    /** Web 浏览专家系统提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#WEB_AGENT_SYS_PROMPT} */

    /** 邮件专家智能体名称 */
    public static final String EMAIL_AGENT_NAME = "邮件专家";

    /** 邮件专家智能体描述 */
    public static final String EMAIL_AGENT_DESCRIPTION =
            "邮件收发与管理。发送邮件、查看收件箱、搜索邮件、回复邮件。不处理即时通知（由通知专家负责）。";

    /** 邮件专家系统提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#EMAIL_AGENT_SYS_PROMPT} */

    /** 系统操作专家智能体名称 */
    public static final String SYSTEM_AGENT_NAME = "系统操作专家";

    /** 系统操作专家智能体描述 */
    public static final String SYSTEM_AGENT_DESCRIPTION =
            "桌面系统操作。获取系统信息、屏幕截图、鼠标键盘操控、本地文件管理（读写/复制/移动/删除）。" +
            "不处理网页操作（由Web专家负责）。";

    /** 系统操作专家系统提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#SYSTEM_AGENT_SYS_PROMPT} */

    /** 桌面自动化专家智能体名称 */
    public static final String DESKTOP_AGENT_NAME = "桌面自动化专家";

    /** 桌面自动化专家智能体描述 */
    public static final String DESKTOP_AGENT_DESCRIPTION =
            "操作其他桌面软件（IDE、编辑器等任意 GUI 程序）。启动程序、枚举/激活窗口、截取界面交视觉模型理解、" +
            "用键鼠点击与输入控制目标程序。跨平台（macOS/Windows/Linux 自动适配，缺原生能力时降级为整屏截图+视觉定位）。" +
            "不处理网页（由 Web 专家负责）、不做本地文件读写（由系统操作专家负责）。";

    /** 桌面自动化专家系统提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#DESKTOP_AGENT_SYS_PROMPT} */

    /** 命令行专家智能体名称 */
    public static final String COMMAND_AGENT_NAME = "命令行专家";

    /** 命令行专家智能体描述 */
    public static final String COMMAND_AGENT_DESCRIPTION =
            "Shell 命令执行。运行编译构建（mvn/gradle/npm）、版本控制（git）、脚本（python/node）、" +
            "进程查看（ps）、网络诊断（ping/curl）等命令。" +
            "不处理文件操作（由系统操作专家负责），高风险命令支持白名单自动记忆。";

    /** 命令行专家系统提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#COMMAND_AGENT_SYS_PROMPT} */

    /** 任务评估专家智能体名称 */
    public static final String EVALUATOR_AGENT_NAME = "任务评估专家";

    /** 任务评估专家智能体描述 */
    public static final String EVALUATOR_AGENT_DESCRIPTION =
            "任务执行质量评估。对已完成的多步骤任务进行评分和改进建议。" +
            "仅在复杂规划的所有子任务执行完毕后调用。";

    /** 任务评估专家系统提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#EVALUATOR_AGENT_SYS_PROMPT} */

    /** 通知专家智能体名称 */
    public static final String NOTIFICATION_AGENT_NAME = "通知专家";

    /** 通知专家智能体描述 */
    public static final String NOTIFICATION_AGENT_DESCRIPTION =
            "多渠道即时通知。通过钉钉/企业微信/飞书/邮件/Webhook发送消息通知。" +
            "不处理邮件收发（由邮件专家负责），仅负责推送通知。";

    /** 通知专家系统提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#NOTIFICATION_AGENT_SYS_PROMPT} */

    /** 主编排智能体系统提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#ORCHESTRATOR_SYS_PROMPT} */

    /** 规划最大子任务数 */
    public static final int PLAN_MAX_SUBTASKS = 8;

    // ==================== 规划模式（MsgHub 多智能体协同） ====================

    /** 规划协调者智能体名称 */
    public static final String PLAN_COORDINATOR_NAME = "规划协调者";

    /** 规划协调者系统提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#PLAN_COORDINATOR_SYS_PROMPT} */

    /** 规划模式专家补充提示词已迁移至 {@link com.javaclaw.prompt.AgentPrompts#PLAN_MODE_EXPERT_SUFFIX} */

    // ==================== 构造与单例 ====================

    private AgentConfig() {
        this.properties = new Properties();
        resolveConfigPath();
        load();
    }

    public static synchronized AgentConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AgentConfig();
        }
        return INSTANCE;
    }

    /**
     * 重新加载配置（工作区切换时调用）
     */
    public void reload() {
        properties.clear();
        resolveConfigPath();
        load();
        log.info("智能体配置已重新加载: {}", configFilePath);
    }

    private void resolveConfigPath() {
        this.configFilePath = WorkspaceManager.getInstance()
                .getCurrentWorkspacePath().resolve(CONFIG_FILE_NAME);
    }

    // ==================== 文件读写 ====================

    /**
     * 从文件加载配置，文件不存在时使用默认值
     */
    private void load() {
        File file = configFilePath.toFile();
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                properties.load(new InputStreamReader(in, "UTF-8"));
                log.info("智能体配置已从文件加载: {}", configFilePath);
            } catch (IOException e) {
                log.warn("加载智能体配置文件失败，使用默认值: {}", e.getMessage());
                setDefaults();
            }
        } else {
            log.info("智能体配置文件不存在，使用默认值: {}", configFilePath);
            setDefaults();
            save();
        }
    }

    /**
     * 设置默认值
     */
    private void setDefaults() {
        properties.setProperty(KEY_PROVIDER_TYPE, DEFAULT_PROVIDER_TYPE);
        properties.setProperty(KEY_BASE_URL, DEFAULT_BASE_URL);
        properties.setProperty(KEY_MODEL_NAME, DEFAULT_MODEL_NAME);
        properties.setProperty(KEY_API_KEY, DEFAULT_API_KEY);
        properties.setProperty(KEY_THINKING_BUDGET, String.valueOf(DEFAULT_THINKING_BUDGET));
        properties.setProperty(KEY_CONNECT_TIMEOUT, String.valueOf(DEFAULT_CONNECT_TIMEOUT));
        properties.setProperty(KEY_READ_TIMEOUT, String.valueOf(DEFAULT_READ_TIMEOUT));
        properties.setProperty(KEY_WRITE_TIMEOUT, String.valueOf(DEFAULT_WRITE_TIMEOUT));
        properties.setProperty(KEY_ORCHESTRATOR_MAX_ITERS, String.valueOf(DEFAULT_ORCHESTRATOR_MAX_ITERS));
        properties.setProperty(KEY_WEB_AGENT_MAX_ITERS, String.valueOf(DEFAULT_WEB_AGENT_MAX_ITERS));
        properties.setProperty(KEY_EMAIL_AGENT_MAX_ITERS, String.valueOf(DEFAULT_EMAIL_AGENT_MAX_ITERS));
        properties.setProperty(KEY_SYSTEM_AGENT_MAX_ITERS, String.valueOf(DEFAULT_SYSTEM_AGENT_MAX_ITERS));
        properties.setProperty(KEY_NOTIFICATION_AGENT_MAX_ITERS, String.valueOf(DEFAULT_NOTIFICATION_AGENT_MAX_ITERS));
        properties.setProperty(KEY_COMMAND_AGENT_MAX_ITERS, String.valueOf(DEFAULT_COMMAND_AGENT_MAX_ITERS));
        properties.setProperty(KEY_THINKING_ENABLED, String.valueOf(DEFAULT_THINKING_ENABLED));
        properties.setProperty(KEY_HTTP_VERSION, DEFAULT_HTTP_VERSION);
        properties.setProperty(KEY_MAX_REPEATED_TOOL_CALLS, String.valueOf(DEFAULT_MAX_REPEATED_TOOL_CALLS));
        properties.setProperty(KEY_LOOP_SIMILARITY_THRESHOLD, String.valueOf(DEFAULT_LOOP_SIMILARITY_THRESHOLD));
        properties.setProperty(KEY_EVALUATOR_PASS_THRESHOLD, String.valueOf(DEFAULT_EVALUATOR_PASS_THRESHOLD));
        properties.setProperty(KEY_EVALUATOR_MAX_RETRIES, String.valueOf(DEFAULT_EVALUATOR_MAX_RETRIES));
        properties.setProperty(KEY_MEMORY_MAX_TOKEN, String.valueOf(DEFAULT_MEMORY_MAX_TOKEN));
        properties.setProperty(KEY_MEMORY_MSG_THRESHOLD, String.valueOf(DEFAULT_MEMORY_MSG_THRESHOLD));
        properties.setProperty(KEY_MEMORY_LAST_KEEP, String.valueOf(DEFAULT_MEMORY_LAST_KEEP));
        properties.setProperty(KEY_MEMORY_TOKEN_RATIO, String.valueOf(DEFAULT_MEMORY_TOKEN_RATIO));
        properties.setProperty(KEY_RETRY_MAX_ATTEMPTS, String.valueOf(DEFAULT_RETRY_MAX_ATTEMPTS));
        properties.setProperty(KEY_RETRY_INITIAL_BACKOFF, String.valueOf(DEFAULT_RETRY_INITIAL_BACKOFF));
        properties.setProperty(KEY_RETRY_MAX_BACKOFF, String.valueOf(DEFAULT_RETRY_MAX_BACKOFF));
        properties.setProperty(KEY_PLAN_MODE_MAX_ROUNDS, String.valueOf(DEFAULT_PLAN_MODE_MAX_ROUNDS));
        properties.setProperty(KEY_PLAN_MODE_MAX_EXPERTS, String.valueOf(DEFAULT_PLAN_MODE_MAX_EXPERTS));
        properties.setProperty(KEY_SCHEDULE_THREAD_POOL_SIZE, String.valueOf(DEFAULT_SCHEDULE_THREAD_POOL_SIZE));
        properties.setProperty(KEY_GEPA_GOAL_ENABLED, String.valueOf(DEFAULT_GEPA_GOAL_ENABLED));
        properties.setProperty(KEY_GEPA_EVAL_INTERVAL, String.valueOf(DEFAULT_GEPA_EVAL_INTERVAL));
        properties.setProperty(KEY_GEPA_EVAL_THRESHOLD, String.valueOf(DEFAULT_GEPA_EVAL_THRESHOLD));
        properties.setProperty(KEY_GEPA_PLAN_ADAPTIVE, String.valueOf(DEFAULT_GEPA_PLAN_ADAPTIVE));
        properties.setProperty(KEY_GEPA_FEEDBACK_MAX_ROUNDS, String.valueOf(DEFAULT_GEPA_FEEDBACK_MAX_ROUNDS));
        properties.setProperty(KEY_RAG_ENABLED, String.valueOf(DEFAULT_RAG_ENABLED));
        properties.setProperty(KEY_RAG_EMBEDDING_PROVIDER, DEFAULT_RAG_EMBEDDING_PROVIDER);
        properties.setProperty(KEY_RAG_EMBEDDING_BASE_URL, DEFAULT_RAG_EMBEDDING_BASE_URL);
        properties.setProperty(KEY_RAG_EMBEDDING_API_KEY, DEFAULT_RAG_EMBEDDING_API_KEY);
        properties.setProperty(KEY_RAG_EMBEDDING_MODEL_NAME, DEFAULT_RAG_EMBEDDING_MODEL_NAME);
        properties.setProperty(KEY_RAG_EMBEDDING_DIMENSIONS, String.valueOf(DEFAULT_RAG_EMBEDDING_DIMENSIONS));
        properties.setProperty(KEY_RAG_CHUNK_SIZE, String.valueOf(DEFAULT_RAG_CHUNK_SIZE));
        properties.setProperty(KEY_RAG_CHUNK_OVERLAP, String.valueOf(DEFAULT_RAG_CHUNK_OVERLAP));
        properties.setProperty(KEY_RAG_RETRIEVE_LIMIT, String.valueOf(DEFAULT_RAG_RETRIEVE_LIMIT));
        properties.setProperty(KEY_RAG_SCORE_THRESHOLD, String.valueOf(DEFAULT_RAG_SCORE_THRESHOLD));
    }

    /**
     * 保存配置到文件
     */
    public synchronized void save() {
        try (OutputStream out = new FileOutputStream(configFilePath.toFile())) {
            properties.store(new OutputStreamWriter(out, "UTF-8"),
                    "JavaClaw 智能体配置");
            log.info("智能体配置已保存: {}", configFilePath);
        } catch (IOException e) {
            log.error("保存智能体配置文件失败", e);
        }
    }

    // ==================== API 连接配置 ====================

    /**
     * 获取模型提供商类型
     *
     * @return "OpenAI"、"DashScope"、"Anthropic"、"Gemini" 或 "Ollama"
     */
    public String getProviderType() {
        return properties.getProperty(KEY_PROVIDER_TYPE, DEFAULT_PROVIDER_TYPE);
    }

    public void setProviderType(String value) {
        properties.setProperty(KEY_PROVIDER_TYPE, value);
    }

    public String getBaseUrl() {
        return properties.getProperty(KEY_BASE_URL, DEFAULT_BASE_URL);
    }

    public void setBaseUrl(String value) {
        properties.setProperty(KEY_BASE_URL, value);
    }

    public String getModelName() {
        return properties.getProperty(KEY_MODEL_NAME, DEFAULT_MODEL_NAME);
    }

    public void setModelName(String value) {
        properties.setProperty(KEY_MODEL_NAME, value);
    }

    public String getApiKey() {
        String raw = properties.getProperty(KEY_API_KEY, DEFAULT_API_KEY);
        return CredentialEncryptor.decrypt(raw);
    }

    public void setApiKey(String value) {
        properties.setProperty(KEY_API_KEY, CredentialEncryptor.encrypt(value));
    }

    // ==================== 分级模型配置（NORMAL / LIGHT） ====================
    //
    // 规则：任一 tier 的 modelName 为空时整组回落到 HIGH（即 api.* 配置）；
    // 这样未启用分级的用户与原有行为完全一致，启用后可单独指定不同提供商/模型。

    /**
     * 普通模型是否单独配置（modelName 非空才视为已配置）
     */
    public boolean isNormalTierConfigured() {
        String name = properties.getProperty(KEY_NORMAL_MODEL_NAME, "").trim();
        return !name.isEmpty();
    }

    public String getNormalProviderType() {
        if (!isNormalTierConfigured()) return getProviderType();
        String raw = properties.getProperty(KEY_NORMAL_PROVIDER_TYPE, "").trim();
        return raw.isEmpty() ? getProviderType() : raw;
    }

    public void setNormalProviderType(String value) {
        properties.setProperty(KEY_NORMAL_PROVIDER_TYPE, value == null ? "" : value);
    }

    public String getNormalBaseUrl() {
        if (!isNormalTierConfigured()) return getBaseUrl();
        String raw = properties.getProperty(KEY_NORMAL_BASE_URL, "").trim();
        return raw.isEmpty() ? getBaseUrl() : raw;
    }

    public void setNormalBaseUrl(String value) {
        properties.setProperty(KEY_NORMAL_BASE_URL, value == null ? "" : value);
    }

    public String getNormalModelName() {
        if (!isNormalTierConfigured()) return getModelName();
        return properties.getProperty(KEY_NORMAL_MODEL_NAME, "").trim();
    }

    public void setNormalModelName(String value) {
        properties.setProperty(KEY_NORMAL_MODEL_NAME, value == null ? "" : value);
    }

    /** 普通模型 API 密钥；未单独配置时回落到 HIGH 的密钥 */
    public String getNormalApiKey() {
        String raw = properties.getProperty(KEY_NORMAL_API_KEY, "").trim();
        if (raw.isEmpty()) return getApiKey();
        return CredentialEncryptor.decrypt(raw);
    }

    public void setNormalApiKey(String value) {
        if (value == null || value.isEmpty()) {
            properties.setProperty(KEY_NORMAL_API_KEY, "");
        } else {
            properties.setProperty(KEY_NORMAL_API_KEY, CredentialEncryptor.encrypt(value));
        }
    }

    /** 普通模型思考模式：未配置时回落到 HIGH 的开关 */
    public boolean isNormalThinkingEnabled() {
        String raw = properties.getProperty(KEY_NORMAL_THINKING_ENABLED);
        if (raw == null || raw.isBlank()) return isThinkingEnabled();
        return Boolean.parseBoolean(raw);
    }

    public void setNormalThinkingEnabled(boolean value) {
        properties.setProperty(KEY_NORMAL_THINKING_ENABLED, String.valueOf(value));
    }

    /**
     * 轻量模型是否单独配置（modelName 非空才视为已配置）
     */
    public boolean isLightTierConfigured() {
        String name = properties.getProperty(KEY_LIGHT_MODEL_NAME, "").trim();
        return !name.isEmpty();
    }

    public String getLightProviderType() {
        if (!isLightTierConfigured()) return getProviderType();
        String raw = properties.getProperty(KEY_LIGHT_PROVIDER_TYPE, "").trim();
        return raw.isEmpty() ? getProviderType() : raw;
    }

    public void setLightProviderType(String value) {
        properties.setProperty(KEY_LIGHT_PROVIDER_TYPE, value == null ? "" : value);
    }

    public String getLightBaseUrl() {
        if (!isLightTierConfigured()) return getBaseUrl();
        String raw = properties.getProperty(KEY_LIGHT_BASE_URL, "").trim();
        return raw.isEmpty() ? getBaseUrl() : raw;
    }

    public void setLightBaseUrl(String value) {
        properties.setProperty(KEY_LIGHT_BASE_URL, value == null ? "" : value);
    }

    public String getLightModelName() {
        if (!isLightTierConfigured()) return getModelName();
        return properties.getProperty(KEY_LIGHT_MODEL_NAME, "").trim();
    }

    public void setLightModelName(String value) {
        properties.setProperty(KEY_LIGHT_MODEL_NAME, value == null ? "" : value);
    }

    /** 轻量模型 API 密钥；未单独配置时回落到 HIGH 的密钥 */
    public String getLightApiKey() {
        String raw = properties.getProperty(KEY_LIGHT_API_KEY, "").trim();
        if (raw.isEmpty()) return getApiKey();
        return CredentialEncryptor.decrypt(raw);
    }

    public void setLightApiKey(String value) {
        if (value == null || value.isEmpty()) {
            properties.setProperty(KEY_LIGHT_API_KEY, "");
        } else {
            properties.setProperty(KEY_LIGHT_API_KEY, CredentialEncryptor.encrypt(value));
        }
    }

    /**
     * 轻量模型思考模式：默认为 false（轻量场景应避免 thinking 阻塞）；
     * 未配置时不回落到 HIGH，确保路由/分类调用不会因 HIGH 开了 thinking 而被拖慢。
     */
    public boolean isLightThinkingEnabled() {
        String raw = properties.getProperty(KEY_LIGHT_THINKING_ENABLED);
        if (raw == null || raw.isBlank()) return false;
        return Boolean.parseBoolean(raw);
    }

    public void setLightThinkingEnabled(boolean value) {
        properties.setProperty(KEY_LIGHT_THINKING_ENABLED, String.valueOf(value));
    }

    // ==================== 模型参数 ====================

    public int getThinkingBudget() {
        return getInt(KEY_THINKING_BUDGET, DEFAULT_THINKING_BUDGET);
    }

    public void setThinkingBudget(int value) {
        properties.setProperty(KEY_THINKING_BUDGET, String.valueOf(value));
    }

    // ==================== 思考模式开关 ====================

    /**
     * 是否启用思考模式
     */
    public boolean isThinkingEnabled() {
        return Boolean.parseBoolean(
                properties.getProperty(KEY_THINKING_ENABLED, String.valueOf(DEFAULT_THINKING_ENABLED)));
    }

    public void setThinkingEnabled(boolean value) {
        properties.setProperty(KEY_THINKING_ENABLED, String.valueOf(value));
    }

    // ==================== HTTP 版本 ====================

    /**
     * 获取 HTTP 版本配置
     *
     * @return "HTTP_1_1" 或 "HTTP_2"
     */
    public String getHttpVersion() {
        return properties.getProperty(KEY_HTTP_VERSION, DEFAULT_HTTP_VERSION);
    }

    public void setHttpVersion(String value) {
        properties.setProperty(KEY_HTTP_VERSION, value);
    }

    /**
     * 是否使用 HTTP/2
     */
    public boolean isHttp2() {
        return "HTTP_2".equals(getHttpVersion());
    }

    // ==================== 超时配置 ====================

    public int getConnectTimeoutSeconds() {
        return getInt(KEY_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
    }

    public void setConnectTimeoutSeconds(int value) {
        properties.setProperty(KEY_CONNECT_TIMEOUT, String.valueOf(value));
    }

    public int getReadTimeoutSeconds() {
        return getInt(KEY_READ_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public void setReadTimeoutSeconds(int value) {
        properties.setProperty(KEY_READ_TIMEOUT, String.valueOf(value));
    }

    public int getWriteTimeoutSeconds() {
        return getInt(KEY_WRITE_TIMEOUT, DEFAULT_WRITE_TIMEOUT);
    }

    /**
     * 单次模型请求总超时（秒）。覆盖 AgentScope MODEL_DEFAULTS 的 5 分钟默认。
     * 取值 ≤0 时回退为默认值（不支持"无限"，避免请求永不失败）。
     */
    public int getModelRequestTimeoutSeconds() {
        int v = getInt(KEY_MODEL_REQUEST_TIMEOUT, DEFAULT_MODEL_REQUEST_TIMEOUT);
        return v > 0 ? v : DEFAULT_MODEL_REQUEST_TIMEOUT;
    }

    public void setModelRequestTimeoutSeconds(int value) {
        properties.setProperty(KEY_MODEL_REQUEST_TIMEOUT, String.valueOf(value));
    }

    public void setWriteTimeoutSeconds(int value) {
        properties.setProperty(KEY_WRITE_TIMEOUT, String.valueOf(value));
    }

    // ==================== 智能体迭代配置 ====================

    public int getOrchestratorMaxIters() {
        return getInt(KEY_ORCHESTRATOR_MAX_ITERS, DEFAULT_ORCHESTRATOR_MAX_ITERS);
    }

    public void setOrchestratorMaxIters(int value) {
        properties.setProperty(KEY_ORCHESTRATOR_MAX_ITERS, String.valueOf(value));
    }

    public int getWebAgentMaxIters() {
        return getInt(KEY_WEB_AGENT_MAX_ITERS, DEFAULT_WEB_AGENT_MAX_ITERS);
    }

    public void setWebAgentMaxIters(int value) {
        properties.setProperty(KEY_WEB_AGENT_MAX_ITERS, String.valueOf(value));
    }

    public int getEmailAgentMaxIters() {
        return getInt(KEY_EMAIL_AGENT_MAX_ITERS, DEFAULT_EMAIL_AGENT_MAX_ITERS);
    }

    public void setEmailAgentMaxIters(int value) {
        properties.setProperty(KEY_EMAIL_AGENT_MAX_ITERS, String.valueOf(value));
    }

    public int getSystemAgentMaxIters() {
        return getInt(KEY_SYSTEM_AGENT_MAX_ITERS, DEFAULT_SYSTEM_AGENT_MAX_ITERS);
    }

    public void setSystemAgentMaxIters(int value) {
        properties.setProperty(KEY_SYSTEM_AGENT_MAX_ITERS, String.valueOf(value));
    }

    public int getNotificationAgentMaxIters() {
        return getInt(KEY_NOTIFICATION_AGENT_MAX_ITERS, DEFAULT_NOTIFICATION_AGENT_MAX_ITERS);
    }

    public void setNotificationAgentMaxIters(int value) {
        properties.setProperty(KEY_NOTIFICATION_AGENT_MAX_ITERS, String.valueOf(value));
    }

    public int getCommandAgentMaxIters() {
        return getInt(KEY_COMMAND_AGENT_MAX_ITERS, DEFAULT_COMMAND_AGENT_MAX_ITERS);
    }

    public void setCommandAgentMaxIters(int value) {
        properties.setProperty(KEY_COMMAND_AGENT_MAX_ITERS, String.valueOf(value));
    }

    // ==================== 循环检测配置 ====================

    public int getMaxRepeatedToolCalls() {
        return getInt(KEY_MAX_REPEATED_TOOL_CALLS, DEFAULT_MAX_REPEATED_TOOL_CALLS);
    }

    public void setMaxRepeatedToolCalls(int value) {
        properties.setProperty(KEY_MAX_REPEATED_TOOL_CALLS, String.valueOf(value));
    }

    public double getLoopSimilarityThreshold() {
        try {
            return Double.parseDouble(properties.getProperty(KEY_LOOP_SIMILARITY_THRESHOLD,
                    String.valueOf(DEFAULT_LOOP_SIMILARITY_THRESHOLD)));
        } catch (NumberFormatException e) {
            return DEFAULT_LOOP_SIMILARITY_THRESHOLD;
        }
    }

    public void setLoopSimilarityThreshold(double value) {
        properties.setProperty(KEY_LOOP_SIMILARITY_THRESHOLD, String.valueOf(value));
    }

    // ==================== 任务评估配置 ====================

    /**
     * 获取任务评估通过阈值（1~5 分）
     */
    public double getEvaluatorPassThreshold() {
        try {
            return Double.parseDouble(properties.getProperty(KEY_EVALUATOR_PASS_THRESHOLD,
                    String.valueOf(DEFAULT_EVALUATOR_PASS_THRESHOLD)));
        } catch (NumberFormatException e) {
            return DEFAULT_EVALUATOR_PASS_THRESHOLD;
        }
    }

    public void setEvaluatorPassThreshold(double value) {
        properties.setProperty(KEY_EVALUATOR_PASS_THRESHOLD, String.valueOf(value));
    }

    /**
     * 获取任务评估最大重试次数
     */
    public int getEvaluatorMaxRetries() {
        return getInt(KEY_EVALUATOR_MAX_RETRIES, DEFAULT_EVALUATOR_MAX_RETRIES);
    }

    public void setEvaluatorMaxRetries(int value) {
        properties.setProperty(KEY_EVALUATOR_MAX_RETRIES, String.valueOf(value));
    }

    // ==================== 重试配置 ====================

    /**
     * 获取最大重试次数（含首次尝试）
     */
    public int getRetryMaxAttempts() {
        return getInt(KEY_RETRY_MAX_ATTEMPTS, DEFAULT_RETRY_MAX_ATTEMPTS);
    }

    public void setRetryMaxAttempts(int value) {
        properties.setProperty(KEY_RETRY_MAX_ATTEMPTS, String.valueOf(value));
    }

    /**
     * 获取首次重试的初始退避时间（秒）
     */
    public int getRetryInitialBackoffSeconds() {
        return getInt(KEY_RETRY_INITIAL_BACKOFF, DEFAULT_RETRY_INITIAL_BACKOFF);
    }

    public void setRetryInitialBackoffSeconds(int value) {
        properties.setProperty(KEY_RETRY_INITIAL_BACKOFF, String.valueOf(value));
    }

    /**
     * 获取重试间最大退避时间（秒）
     */
    public int getRetryMaxBackoffSeconds() {
        return getInt(KEY_RETRY_MAX_BACKOFF, DEFAULT_RETRY_MAX_BACKOFF);
    }

    public void setRetryMaxBackoffSeconds(int value) {
        properties.setProperty(KEY_RETRY_MAX_BACKOFF, String.valueOf(value));
    }

    // ==================== 上下文记忆配置 ====================

    /**
     * 获取上下文窗口最大 token 数
     */
    public long getMemoryMaxToken() {
        try {
            return Long.parseLong(properties.getProperty(KEY_MEMORY_MAX_TOKEN,
                    String.valueOf(DEFAULT_MEMORY_MAX_TOKEN)));
        } catch (NumberFormatException e) {
            return DEFAULT_MEMORY_MAX_TOKEN;
        }
    }

    public void setMemoryMaxToken(long value) {
        properties.setProperty(KEY_MEMORY_MAX_TOKEN, String.valueOf(value));
    }

    /**
     * 获取触发压缩的消息数阈值
     */
    public int getMemoryMsgThreshold() {
        return getInt(KEY_MEMORY_MSG_THRESHOLD, DEFAULT_MEMORY_MSG_THRESHOLD);
    }

    public void setMemoryMsgThreshold(int value) {
        properties.setProperty(KEY_MEMORY_MSG_THRESHOLD, String.valueOf(value));
    }

    /**
     * 获取保留最近不压缩的消息数
     */
    public int getMemoryLastKeep() {
        return getInt(KEY_MEMORY_LAST_KEEP, DEFAULT_MEMORY_LAST_KEEP);
    }

    public void setMemoryLastKeep(int value) {
        properties.setProperty(KEY_MEMORY_LAST_KEEP, String.valueOf(value));
    }

    /**
     * 获取触发压缩的 token 占用比例（0.0~1.0）
     */
    public double getMemoryTokenRatio() {
        try {
            return Double.parseDouble(properties.getProperty(KEY_MEMORY_TOKEN_RATIO,
                    String.valueOf(DEFAULT_MEMORY_TOKEN_RATIO)));
        } catch (NumberFormatException e) {
            return DEFAULT_MEMORY_TOKEN_RATIO;
        }
    }

    public void setMemoryTokenRatio(double value) {
        properties.setProperty(KEY_MEMORY_TOKEN_RATIO, String.valueOf(value));
    }

    // ==================== 记忆检索 / 蒸馏（EclipseStore 记忆基座） ====================

    /** 每轮注入的事实 Top-K（默认 8） */
    public int getMemoryRecallTopK() {
        return getInt("memory.recall.topk", 8);
    }

    /** 每轮注入的相关情景条数（默认 3） */
    public int getMemoryRecallEpisodes() {
        return getInt("memory.recall.episodes", 3);
    }

    /** 检索相似度下限（默认 0.3，低于此值不注入） */
    public double getMemoryRecallThreshold() {
        return getDouble("memory.recall.threshold", 0.3);
    }

    /** 注入字符预算上限（默认 8000） */
    public int getMemoryRecallMaxChars() {
        return getInt("memory.recall.maxchars", 8000);
    }

    /** 蒸馏去重相似度阈值：候选与既有事实相似度≥此值则合并而非新增（默认 0.9） */
    public double getMemoryDistillDedupThreshold() {
        return getDouble("memory.distill.dedup.threshold", 0.9);
    }

    /** 蒸馏所需最短用户输入字符数（默认 10） */
    public int getMemoryDistillMinInput() {
        return getInt("memory.distill.min.input", 10);
    }

    // ==================== 规划模式配置 ====================

    /**
     * 获取规划模式最大讨论轮数
     */
    public int getPlanModeMaxRounds() {
        return getInt(KEY_PLAN_MODE_MAX_ROUNDS, DEFAULT_PLAN_MODE_MAX_ROUNDS);
    }

    public void setPlanModeMaxRounds(int value) {
        properties.setProperty(KEY_PLAN_MODE_MAX_ROUNDS, String.valueOf(value));
    }

    /**
     * 获取规划模式最多参与专家数
     */
    public int getPlanModeMaxExperts() {
        return getInt(KEY_PLAN_MODE_MAX_EXPERTS, DEFAULT_PLAN_MODE_MAX_EXPERTS);
    }

    public void setPlanModeMaxExperts(int value) {
        properties.setProperty(KEY_PLAN_MODE_MAX_EXPERTS, String.valueOf(value));
    }

    // ==================== 定时任务配置 ====================

    /**
     * 获取定时任务线程池大小
     */
    public int getScheduleThreadPoolSize() {
        return getInt(KEY_SCHEDULE_THREAD_POOL_SIZE, DEFAULT_SCHEDULE_THREAD_POOL_SIZE);
    }

    public void setScheduleThreadPoolSize(int value) {
        properties.setProperty(KEY_SCHEDULE_THREAD_POOL_SIZE, String.valueOf(value));
    }

    // ==================== 任务管理配置 ====================

    /** 任务智能体最大迭代次数 */
    public int getTaskAgentMaxIters() {
        return getInt(KEY_TASK_AGENT_MAX_ITERS, DEFAULT_TASK_AGENT_MAX_ITERS);
    }

    /** 最大并发运行任务数 */
    public int getTaskMaxConcurrent() {
        return getInt(KEY_TASK_MAX_CONCURRENT, DEFAULT_TASK_MAX_CONCURRENT);
    }

    /**
     * 子任务连续工具错误的重试上限（达到即终止该子任务）。最小 1；取值 ≤0 时回退默认。
     */
    public int getSubtaskToolErrorMax() {
        int v = getInt(KEY_SUBTASK_TOOL_ERROR_MAX, DEFAULT_SUBTASK_TOOL_ERROR_MAX);
        return v > 0 ? v : DEFAULT_SUBTASK_TOOL_ERROR_MAX;
    }

    public void setSubtaskToolErrorMax(int value) {
        properties.setProperty(KEY_SUBTASK_TOOL_ERROR_MAX, String.valueOf(value));
    }

    // ==================== 任务各阶段超时配置 ====================

    /** 子任务执行智能体 .block() 超时（秒） */
    public int getTaskSubtaskExecutorTimeoutSeconds() {
        return getInt(KEY_TASK_SUBTASK_EXECUTOR_TIMEOUT, DEFAULT_TASK_SUBTASK_EXECUTOR_TIMEOUT);
    }

    /** 验收阶段调用 .block() 超时（秒） */
    public int getTaskChallengeTimeoutSeconds() {
        return getInt("task.timeout.challenge.seconds", 300);
    }

    // ==================== SDD 托管任务配置 ====================

    /** SDD 单个实现项执行的整体阻塞超时（秒）；≤0 回退默认 */
    public int getSddExecTimeoutSeconds() {
        int v = getInt(KEY_SDD_EXEC_TIMEOUT, DEFAULT_SDD_EXEC_TIMEOUT);
        return v > 0 ? v : DEFAULT_SDD_EXEC_TIMEOUT;
    }

    /** SDD 结构化阶段（提案/规格/计划/补做）阻塞超时（秒）；≤0 回退默认 */
    public int getSddStructuredTimeoutSeconds() {
        int v = getInt(KEY_SDD_STRUCTURED_TIMEOUT, DEFAULT_SDD_STRUCTURED_TIMEOUT);
        return v > 0 ? v : DEFAULT_SDD_STRUCTURED_TIMEOUT;
    }

    /** SDD 实现执行体单项 ReAct 迭代上限；≤0 回退默认 */
    public int getSddExecMaxIters() {
        int v = getInt(KEY_SDD_EXEC_MAX_ITERS, DEFAULT_SDD_EXEC_MAX_ITERS);
        return v > 0 ? v : DEFAULT_SDD_EXEC_MAX_ITERS;
    }

    /**
     * 是否启用托管任务高风险工具的"目录内自动放行"。
     *
     * <p>开启后，托管任务执行期间遇到目录作用域高风险工具（文件写/移动/复制/删除、命令执行），
     * 由风险评估智能体判定其影响范围；若完全限于任务设置的工作目录内则自动放行，不再弹人工确认。
     * 关闭则回退为一律人工确认。默认开启。</p>
     */
    public boolean isTaskRiskAutoApproveEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_TASK_RISK_AUTOAPPROVE,
                String.valueOf(DEFAULT_TASK_RISK_AUTOAPPROVE)));
    }

    public void setTaskRiskAutoApproveEnabled(boolean value) {
        properties.setProperty(KEY_TASK_RISK_AUTOAPPROVE, String.valueOf(value));
    }

    // ==================== RAG 知识库配置 ====================

    /**
     * 是否启用 RAG 知识库
     */
    public boolean isRagEnabled() {
        return Boolean.parseBoolean(
                properties.getProperty(KEY_RAG_ENABLED, String.valueOf(DEFAULT_RAG_ENABLED)));
    }

    public void setRagEnabled(boolean value) {
        properties.setProperty(KEY_RAG_ENABLED, String.valueOf(value));
    }

    /**
     * 获取嵌入模型提供商类型
     *
     * @return "OpenAI"、"DashScope" 或 "Ollama"
     */
    public String getRagEmbeddingProvider() {
        return properties.getProperty(KEY_RAG_EMBEDDING_PROVIDER, DEFAULT_RAG_EMBEDDING_PROVIDER);
    }

    public void setRagEmbeddingProvider(String value) {
        properties.setProperty(KEY_RAG_EMBEDDING_PROVIDER, value);
    }

    public String getRagEmbeddingBaseUrl() {
        return properties.getProperty(KEY_RAG_EMBEDDING_BASE_URL, DEFAULT_RAG_EMBEDDING_BASE_URL);
    }

    public void setRagEmbeddingBaseUrl(String value) {
        properties.setProperty(KEY_RAG_EMBEDDING_BASE_URL, value);
    }

    public String getRagEmbeddingApiKey() {
        String raw = properties.getProperty(KEY_RAG_EMBEDDING_API_KEY, DEFAULT_RAG_EMBEDDING_API_KEY);
        return CredentialEncryptor.decrypt(raw);
    }

    public void setRagEmbeddingApiKey(String value) {
        properties.setProperty(KEY_RAG_EMBEDDING_API_KEY, CredentialEncryptor.encrypt(value));
    }

    public String getRagEmbeddingModelName() {
        return properties.getProperty(KEY_RAG_EMBEDDING_MODEL_NAME, DEFAULT_RAG_EMBEDDING_MODEL_NAME);
    }

    public void setRagEmbeddingModelName(String value) {
        properties.setProperty(KEY_RAG_EMBEDDING_MODEL_NAME, value);
    }

    public int getRagEmbeddingDimensions() {
        return getInt(KEY_RAG_EMBEDDING_DIMENSIONS, DEFAULT_RAG_EMBEDDING_DIMENSIONS);
    }

    public void setRagEmbeddingDimensions(int value) {
        properties.setProperty(KEY_RAG_EMBEDDING_DIMENSIONS, String.valueOf(value));
    }

    public int getRagChunkSize() {
        return getInt(KEY_RAG_CHUNK_SIZE, DEFAULT_RAG_CHUNK_SIZE);
    }

    public void setRagChunkSize(int value) {
        properties.setProperty(KEY_RAG_CHUNK_SIZE, String.valueOf(value));
    }

    public int getRagChunkOverlap() {
        return getInt(KEY_RAG_CHUNK_OVERLAP, DEFAULT_RAG_CHUNK_OVERLAP);
    }

    public void setRagChunkOverlap(int value) {
        properties.setProperty(KEY_RAG_CHUNK_OVERLAP, String.valueOf(value));
    }

    public int getRagRetrieveLimit() {
        return getInt(KEY_RAG_RETRIEVE_LIMIT, DEFAULT_RAG_RETRIEVE_LIMIT);
    }

    public void setRagRetrieveLimit(int value) {
        properties.setProperty(KEY_RAG_RETRIEVE_LIMIT, String.valueOf(value));
    }

    public double getRagScoreThreshold() {
        try {
            return Double.parseDouble(properties.getProperty(KEY_RAG_SCORE_THRESHOLD,
                    String.valueOf(DEFAULT_RAG_SCORE_THRESHOLD)));
        } catch (NumberFormatException e) {
            return DEFAULT_RAG_SCORE_THRESHOLD;
        }
    }

    public void setRagScoreThreshold(double value) {
        properties.setProperty(KEY_RAG_SCORE_THRESHOLD, String.valueOf(value));
    }

    public boolean isFirstUseGuidanceDone() {
        return Boolean.parseBoolean(properties.getProperty(KEY_FIRST_USE_GUIDANCE_DONE, "false"));
    }

    public void setFirstUseGuidanceDone(boolean value) {
        properties.setProperty(KEY_FIRST_USE_GUIDANCE_DONE, String.valueOf(value));
    }

    /** 关闭主窗口时最小化到系统托盘后台常驻（默认 true）；false 时关闭即退出 */
    public boolean isTrayMinimizeOnClose() {
        return Boolean.parseBoolean(properties.getProperty(KEY_TRAY_MINIMIZE_ON_CLOSE, "true"));
    }

    public void setTrayMinimizeOnClose(boolean value) {
        properties.setProperty(KEY_TRAY_MINIMIZE_ON_CLOSE, String.valueOf(value));
    }

    /** 界面风格主题 ID（emerald/midnight/carbon/sapphire/ocean/plum/terracotta/honey/graphite，默认 emerald，按工作区记忆） */
    public String getUiTheme() {
        return properties.getProperty(KEY_UI_THEME, "emerald");
    }

    public void setUiTheme(String value) {
        properties.setProperty(KEY_UI_THEME, value);
    }

    /** 普通场景工具确认超时（秒） */
    public int getConfirmationTimeoutDefault() {
        return getInt(KEY_CONFIRMATION_TIMEOUT_DEFAULT, DEFAULT_CONFIRMATION_TIMEOUT_DEFAULT);
    }

    public void setConfirmationTimeoutDefault(int value) {
        properties.setProperty(KEY_CONFIRMATION_TIMEOUT_DEFAULT, String.valueOf(value));
    }

    /** 托管任务场景工具确认超时（秒） */
    public int getConfirmationTimeoutManaged() {
        return getInt(KEY_CONFIRMATION_TIMEOUT_MANAGED, DEFAULT_CONFIRMATION_TIMEOUT_MANAGED);
    }

    public void setConfirmationTimeoutManaged(int value) {
        properties.setProperty(KEY_CONFIRMATION_TIMEOUT_MANAGED, String.valueOf(value));
    }

    /** 任务事件 JSONL 保留天数（0 或负数 = 不清理） */
    public int getTaskEventsRetentionDays() {
        return getInt(KEY_TASK_EVENTS_RETENTION_DAYS, DEFAULT_TASK_EVENTS_RETENTION_DAYS);
    }

    public void setTaskEventsRetentionDays(int value) {
        properties.setProperty(KEY_TASK_EVENTS_RETENTION_DAYS, String.valueOf(value));
    }

    public boolean isTaskVerificationEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_TASK_VERIFICATION_ENABLED, "true"));
    }

    public boolean isToolRoutingEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_TOOL_ROUTING_ENABLED, "true"));
    }

    public void setToolRoutingEnabled(boolean enabled) {
        properties.setProperty(KEY_TOOL_ROUTING_ENABLED, String.valueOf(enabled));
    }

    // ==================== GEPA 配置 ====================

    /** 是否启用 GEPA 目标自动分解 */
    public boolean isGepaGoalEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_GEPA_GOAL_ENABLED,
                String.valueOf(DEFAULT_GEPA_GOAL_ENABLED)));
    }

    public void setGepaGoalEnabled(boolean value) {
        properties.setProperty(KEY_GEPA_GOAL_ENABLED, String.valueOf(value));
    }

    /** 过程评估触发间隔（每 N 次真实工具调用触发一次评估） */
    public int getGepaEvalInterval() {
        return getInt(KEY_GEPA_EVAL_INTERVAL, DEFAULT_GEPA_EVAL_INTERVAL);
    }

    public void setGepaEvalInterval(int value) {
        properties.setProperty(KEY_GEPA_EVAL_INTERVAL, String.valueOf(value));
    }

    /** 过程评估触发修正的最低评分阈值（< 该值时 needsCorrection=true） */
    public double getGepaEvalThreshold() {
        try {
            return Double.parseDouble(properties.getProperty(KEY_GEPA_EVAL_THRESHOLD,
                    String.valueOf(DEFAULT_GEPA_EVAL_THRESHOLD)));
        } catch (NumberFormatException e) {
            return DEFAULT_GEPA_EVAL_THRESHOLD;
        }
    }

    public void setGepaEvalThreshold(double value) {
        properties.setProperty(KEY_GEPA_EVAL_THRESHOLD, String.valueOf(value));
    }

    /** 是否启用自适应计划调整 */
    public boolean isGepaPlanAdaptive() {
        return Boolean.parseBoolean(properties.getProperty(KEY_GEPA_PLAN_ADAPTIVE,
                String.valueOf(DEFAULT_GEPA_PLAN_ADAPTIVE)));
    }

    public void setGepaPlanAdaptive(boolean value) {
        properties.setProperty(KEY_GEPA_PLAN_ADAPTIVE, String.valueOf(value));
    }

    /** 单次 streamChat 最大评估 + 反馈轮次（防止死循环） */
    public int getGepaFeedbackMaxRounds() {
        return getInt(KEY_GEPA_FEEDBACK_MAX_ROUNDS, DEFAULT_GEPA_FEEDBACK_MAX_ROUNDS);
    }

    public void setGepaFeedbackMaxRounds(int value) {
        properties.setProperty(KEY_GEPA_FEEDBACK_MAX_ROUNDS, String.valueOf(value));
    }

    // ==================== JShell 执行配置 ====================

    /** JShell 求值默认超时秒数（jshell_exec / jshell_run_script，工具调用可逐次覆盖，硬上限 600） */
    public int getJshellExecTimeoutSeconds() {
        return getInt(KEY_JSHELL_EXEC_TIMEOUT, 60);
    }

    // ==================== 技能进化配置 ====================

    /**
     * 技能进化模式（agent 自我学习沉淀技能的自治程度）：
     * <ul>
     *   <li>{@code off} — 关闭：skill_manage 工具拒绝写入，SkillCurator 不蒸馏</li>
     *   <li>{@code suggest} — 建议（默认）：提案入待审队列，经用户审阅采纳后落盘</li>
     *   <li>{@code auto} — 自动：直接落盘 + Toast 通知（user-modified 技能仍强制降级为提案）</li>
     * </ul>
     */
    public String getSkillEvolutionMode() {
        String raw = properties.getProperty(KEY_SKILL_EVOLUTION_MODE, "suggest").strip().toLowerCase();
        return switch (raw) {
            case "off", "auto" -> raw;
            default -> "suggest";
        };
    }

    public void setSkillEvolutionMode(String mode) {
        properties.setProperty(KEY_SKILL_EVOLUTION_MODE, mode);
    }

    /** 触发技能蒸馏的最小工具调用数（对齐 Hermes「复杂任务 ≥5 次工具调用」阈值） */
    public int getSkillEvolutionMinTools() {
        return getInt(KEY_SKILL_EVOLUTION_MIN_TOOLS, 5);
    }

    public void setSkillEvolutionMinTools(int value) {
        properties.setProperty(KEY_SKILL_EVOLUTION_MIN_TOOLS, String.valueOf(value));
    }

    /** 触发技能蒸馏的轮次滑窗成功率门槛 */
    public double getSkillEvolutionSuccessThreshold() {
        return getDouble(KEY_SKILL_EVOLUTION_SUCCESS_THRESHOLD, 0.6);
    }

    public void setSkillEvolutionSuccessThreshold(double value) {
        properties.setProperty(KEY_SKILL_EVOLUTION_SUCCESS_THRESHOLD, String.valueOf(value));
    }

    /** 被用户拒绝的技能提案冷却天数（冷却期内同指纹不再提案） */
    public int getSkillCurationCooldownDays() {
        return getInt(KEY_SKILL_CURATION_COOLDOWN_DAYS, 7);
    }

    /** 同指纹提案去重窗口（小时） */
    public int getSkillCurationDedupHours() {
        return getInt(KEY_SKILL_CURATION_DEDUP_HOURS, 24);
    }

    /** 低成功率技能判定阈值（低于该值的技能优先成为 patch 候选） */
    public double getSkillUsageLowSuccessThreshold() {
        return getDouble(KEY_SKILL_USAGE_LOWSUCCESS_THRESHOLD, 0.5);
    }

    /** 低成功率判定的最小样本数（样本不足不判定） */
    public int getSkillUsageLowSuccessMinSamples() {
        return getInt(KEY_SKILL_USAGE_LOWSUCCESS_MINSAMPLES, 5);
    }

    /** 是否在系统提示词中常驻「经验沉淀」nudge 提示 */
    public boolean isSkillNudgeEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_SKILL_NUDGE_ENABLED, "true"));
    }

    public void setSkillNudgeEnabled(boolean enabled) {
        properties.setProperty(KEY_SKILL_NUDGE_ENABLED, String.valueOf(enabled));
    }

    /** 是否启用技能包（bundles） */
    public boolean isSkillBundlesEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_SKILL_BUNDLES_ENABLED, "true"));
    }

    public void setSkillBundlesEnabled(boolean enabled) {
        properties.setProperty(KEY_SKILL_BUNDLES_ENABLED, String.valueOf(enabled));
    }

    // ==================== 辅助方法 ====================

    /**
     * 重置所有配置为默认值
     */
    public void resetToDefaults() {
        properties.setProperty(KEY_PROVIDER_TYPE, DEFAULT_PROVIDER_TYPE);
        properties.setProperty(KEY_BASE_URL, DEFAULT_BASE_URL);
        properties.setProperty(KEY_MODEL_NAME, DEFAULT_MODEL_NAME);
        properties.setProperty(KEY_API_KEY, DEFAULT_API_KEY);
        properties.setProperty(KEY_THINKING_BUDGET, String.valueOf(DEFAULT_THINKING_BUDGET));
        properties.setProperty(KEY_CONNECT_TIMEOUT, String.valueOf(DEFAULT_CONNECT_TIMEOUT));
        properties.setProperty(KEY_READ_TIMEOUT, String.valueOf(DEFAULT_READ_TIMEOUT));
        properties.setProperty(KEY_WRITE_TIMEOUT, String.valueOf(DEFAULT_WRITE_TIMEOUT));
        properties.setProperty(KEY_ORCHESTRATOR_MAX_ITERS, String.valueOf(DEFAULT_ORCHESTRATOR_MAX_ITERS));
        properties.setProperty(KEY_WEB_AGENT_MAX_ITERS, String.valueOf(DEFAULT_WEB_AGENT_MAX_ITERS));
        properties.setProperty(KEY_EMAIL_AGENT_MAX_ITERS, String.valueOf(DEFAULT_EMAIL_AGENT_MAX_ITERS));
        properties.setProperty(KEY_SYSTEM_AGENT_MAX_ITERS, String.valueOf(DEFAULT_SYSTEM_AGENT_MAX_ITERS));
        properties.setProperty(KEY_NOTIFICATION_AGENT_MAX_ITERS, String.valueOf(DEFAULT_NOTIFICATION_AGENT_MAX_ITERS));
        properties.setProperty(KEY_COMMAND_AGENT_MAX_ITERS, String.valueOf(DEFAULT_COMMAND_AGENT_MAX_ITERS));
        properties.setProperty(KEY_HTTP_VERSION, DEFAULT_HTTP_VERSION);
        properties.setProperty(KEY_THINKING_ENABLED, String.valueOf(DEFAULT_THINKING_ENABLED));
        properties.setProperty(KEY_MAX_REPEATED_TOOL_CALLS, String.valueOf(DEFAULT_MAX_REPEATED_TOOL_CALLS));
        properties.setProperty(KEY_LOOP_SIMILARITY_THRESHOLD, String.valueOf(DEFAULT_LOOP_SIMILARITY_THRESHOLD));
        properties.setProperty(KEY_EVALUATOR_PASS_THRESHOLD, String.valueOf(DEFAULT_EVALUATOR_PASS_THRESHOLD));
        properties.setProperty(KEY_EVALUATOR_MAX_RETRIES, String.valueOf(DEFAULT_EVALUATOR_MAX_RETRIES));
        properties.setProperty(KEY_MEMORY_MAX_TOKEN, String.valueOf(DEFAULT_MEMORY_MAX_TOKEN));
        properties.setProperty(KEY_MEMORY_MSG_THRESHOLD, String.valueOf(DEFAULT_MEMORY_MSG_THRESHOLD));
        properties.setProperty(KEY_MEMORY_LAST_KEEP, String.valueOf(DEFAULT_MEMORY_LAST_KEEP));
        properties.setProperty(KEY_MEMORY_TOKEN_RATIO, String.valueOf(DEFAULT_MEMORY_TOKEN_RATIO));
        properties.setProperty(KEY_RETRY_MAX_ATTEMPTS, String.valueOf(DEFAULT_RETRY_MAX_ATTEMPTS));
        properties.setProperty(KEY_RETRY_INITIAL_BACKOFF, String.valueOf(DEFAULT_RETRY_INITIAL_BACKOFF));
        properties.setProperty(KEY_RETRY_MAX_BACKOFF, String.valueOf(DEFAULT_RETRY_MAX_BACKOFF));
        properties.setProperty(KEY_PLAN_MODE_MAX_ROUNDS, String.valueOf(DEFAULT_PLAN_MODE_MAX_ROUNDS));
        properties.setProperty(KEY_PLAN_MODE_MAX_EXPERTS, String.valueOf(DEFAULT_PLAN_MODE_MAX_EXPERTS));
        properties.setProperty(KEY_SCHEDULE_THREAD_POOL_SIZE, String.valueOf(DEFAULT_SCHEDULE_THREAD_POOL_SIZE));
        properties.setProperty(KEY_CONFIRMATION_TIMEOUT_DEFAULT, String.valueOf(DEFAULT_CONFIRMATION_TIMEOUT_DEFAULT));
        properties.setProperty(KEY_CONFIRMATION_TIMEOUT_MANAGED, String.valueOf(DEFAULT_CONFIRMATION_TIMEOUT_MANAGED));
        save();
        log.info("配置已重置为默认值");
    }

    /**
     * 获取配置文件路径（用于界面显示）
     */
    public String getConfigFilePath() {
        return configFilePath.toString();
    }

    private int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
