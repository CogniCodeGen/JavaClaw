package com.javaclaw.loop;

/**
 * 循环子系统的全局常量集中管理。
 *
 * <p>本类只承载「固定字符串」与「内置默认阈值」，杜绝这些字面量散落在各处形成魔法值：</p>
 * <ul>
 *   <li><b>收尾标记</b>——循环执行体每轮结尾自报判定用的标记文本，同时被提示词
 *       ({@link com.javaclaw.prompt.LoopPrompts}) 与解析器 ({@link SentinelParser}) 引用，
 *       保证「模型写的」与「代码认的」是同一份来源；</li>
 *   <li><b>成功准则类型码</b>——与 {@link com.javaclaw.agent.goal.SuccessCriterion#type} 的
 *       五种取值约定一致，集中于此供 {@link CriterionVerifier} 分派；</li>
 *   <li><b>事件流标识</b>——发给 UI 的进度/状态事件的稳定 id 与类型 kind；</li>
 *   <li><b>内置默认值</b>——面向用户的键已由 {@code AgentConfig} 读取（loop.* 配置项，两处数值
 *       有意各自维护、避免 config ↔ loop 依赖成环，见 CLAUDE.md）；本类保留引擎内部阈值
 *       （连败/空转/相似度等）不对用户暴露。</li>
 * </ul>
 */
public final class LoopConstants {

    private LoopConstants() {}

    // ==================== 收尾标记（执行体每轮结尾的自报判定行） ====================

    /** 判定行统一前缀：代码只解析以此开头的行，其余正文一律忽略。 */
    public static final String JUDGMENT_LINE_PREFIX = "【判定】";

    /** 判定内容：已完成（执行体「提议」完成，是否采信由核验决定）。 */
    public static final String JUDGMENT_DONE = "已完成";

    /** 判定内容：未完成（执行体「提议」继续）。 */
    public static final String JUDGMENT_NOT_DONE = "未完成";

    // ==================== 成功准则类型码（与 SuccessCriterion.type 约定一致） ====================

    /** 命令成功退出（退出码为零）。 */
    public static final String CRITERION_COMMAND_EXIT_ZERO = "command_exit_zero";
    /** 产物存在（文件/目录路径）。 */
    public static final String CRITERION_ARTIFACT_EXISTS = "artifact_exists";
    /** 输出包含字面关键词（子串匹配）。 */
    public static final String CRITERION_OUTPUT_CONTAINS = "output_contains";
    /** 外部检查（URL 200、邮件送达等，无法本地确定性核验）。 */
    public static final String CRITERION_EXTERNAL_CHECK = "external_check";
    /** 描述性标准（只能靠判断，交验收员）。 */
    public static final String CRITERION_FREEFORM = "freeform";

    // ==================== 事件流标识（发送给 UI 的进度/状态事件） ====================

    /** 进度阶段 id 前缀，后接轮次号（如 {@code loop-iter-3}）。 */
    public static final String EVENT_STAGE_PREFIX = "loop-iter-";
    /** 每轮显示名前缀（如「第 3 轮」）。 */
    public static final String EVENT_STAGE_LABEL_PREFIX = "第 ";
    /** 每轮显示名后缀。 */
    public static final String EVENT_STAGE_LABEL_SUFFIX = " 轮";
    /** 循环状态自定义事件的 kind（Custom 事件负载为 {@link com.javaclaw.loop.model.LoopStatus}）。 */
    public static final String EVENT_STATUS_KIND = "loop_status";

    // ==================== 内置默认值（AgentConfig loop.* 键的引擎侧缺省，两处有意各自维护） ====================

    /** 最大迭代轮数上限，防无限循环。 */
    public static final int DEFAULT_MAX_ITERATIONS = 25;
    /** 用量额度上限；{@code 0} 表示不限。 */
    public static final long DEFAULT_TOKEN_BUDGET = 0L;
    /** 单个循环整体墙钟超时（秒）；默认 1 小时。 */
    public static final long DEFAULT_MAX_WALLCLOCK_SECONDS = 3600L;
    /** 自驱模式的轮间延迟（秒）：0 表示上一轮结束立即开下一轮。 */
    public static final long DEFAULT_SELF_PACED_DELAY_SECONDS = 0L;
    // 注：定时模式的轮间延迟默认值由 AgentConfig.DEFAULT_LOOP_INTERVAL_DELAY_SECONDS 单点持有
    // （用户可配、经 LoopService 读取），此处不再冗余声明——避免两个 300L 各自漂移。

    // ==================== 引擎内部阈值（不对用户暴露的判定常量） ====================

    /** 连续失败达到该次数则停止；第一次先给重试机会（对齐 ExecutionMonitor 的连败阈值）。 */
    public static final int CONSECUTIVE_FAIL_LIMIT = 2;
    /** 连续无进展达到该轮数则判定「收敛不了」并停止。 */
    public static final int NO_PROGRESS_ROUND_LIMIT = 2;
    /**
     * 全部客观准则已满足、但执行体连续沉默（未按协议 loop_report 也无未完成哨兵）达到该轮数后，
     * 即以客观核验为准判定完成。给执行体几轮宽限按协议确认，超限则不再空等——尤其 INTERVAL
     * 免停滞计数，否则「准则全过 + 执行体沉默」会空转烧满轮数/墙钟上限。执行体<b>主动</b>报未完成
     * 时不走此路（继续尊重执行体），本阈值只治「沉默」。
     */
    public static final int CRITERIA_MET_SILENT_GRACE_ROUNDS = 2;
    /**
     * 本轮输出与上轮相似度高于该阈值即视为「雷同、无新意」（复用双字母组 Jaccard 相似度）。
     *
     * <p>注意：输出新颖度<b>只能作为无准则自由目标的最后兜底</b>——LLM 换措辞重写即可轻松
     * 低于此阈值，文本变化不能证明工作推进。有准则目标的进展以准则高水位/行动指纹为准。</p>
     */
    public static final double OUTPUT_SIMILARITY_THRESHOLD = 0.92;

    /** 判定行里「剩余」小节的标记词（提示词与解析器共用，保证单一来源）。 */
    public static final String JUDGMENT_REMAINING_MARKER = "剩余";

    /** 连续两轮自报「剩余」相似度达到该阈值即视为「卡在同一处」（自我供认的停滞）。 */
    public static final double REMAINING_SIMILARITY_THRESHOLD = 0.85;

    /**
     * 验收员/停滞仲裁单次结构化判定的阻塞超时（秒）。
     *
     * <p>与单轮超时（loop.iteration.timeout.seconds）、验证命令超时
     * （loop.verify.timeout.seconds）同属循环内的阻塞调用超时，前两者已因「慢模型/慢命令
     * 被误杀」提为配置键；验收判定带只读核查工具（HIGH 档、maxIters=5、真读文件），
     * 慢模型下超时会逐轮判「未达成」使 done 永不可达——排障时先查这里。</p>
     */
    public static final long JUDGE_TIMEOUT_SECONDS = 120L;

    // ==================== 结构化汇报工具（loop_report，取代哨兵行的首选通道） ====================

    /** 轮次汇报工具名（执行体每轮结束必须调用；提示词与工具注册共用此常量）。 */
    public static final String REPORT_TOOL_NAME = "loop_report";

    /** 自节奏模式下模型可建议的下轮延迟上限（秒）——防模型报出离谱的等待时长。 */
    public static final long MAX_SELF_PACED_DELAY_SECONDS = 1800L;

    // ==================== 循环指令（无参数 UI 时从输入首行解析 @loop ...） ====================

    /** 指令行前缀：输入首行以此开头时解析为循环参数，其余行为目标正文。 */
    public static final String DIRECTIVE_PREFIX = "@loop";
    /** 指令键：轮间间隔（如 {@code interval=5m}，触发定时节奏）。 */
    public static final String DIRECTIVE_KEY_INTERVAL = "interval";
    /** 指令键：最大轮数（如 {@code max=20}）。 */
    public static final String DIRECTIVE_KEY_MAX = "max";
    /** 指令键：是否启用模型验收员（如 {@code judge=on}）。 */
    public static final String DIRECTIVE_KEY_JUDGE = "judge";
    /** 指令键：工作目录（如 {@code workdir=/path/to/project}，不支持含空格路径）。 */
    public static final String DIRECTIVE_KEY_WORKDIR = "workdir";

    // ==================== 接力上下文 ====================

    /** SUMMARY 接力模式下，某轮缺失结构化汇报时用产出截断兜底的字符数。 */
    public static final int SUMMARY_FALLBACK_CHARS = 300;
}
