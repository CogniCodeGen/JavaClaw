package com.javaclaw.loop.agent;

import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.loop.LoopConstants;
import com.javaclaw.loop.model.LoopReport;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 循环轮次汇报工具：执行体每轮结束前必须调用，向循环引擎提交结构化汇报。
 *
 * <p>仿 Claude Code 的 ScheduleWakeup 原语——用<b>工具调用</b>取代哨兵文本行作为
 * 首选信号通道：结构化参数没有解析歧义，且顺带承载「自报下轮延迟」（声明式等待，
 * 轮询场景不再被停滞检测误杀）与「等待原因」（用户透明度）。</p>
 *
 * <p>线程模型：<b>一轮一实例</b>——runner 每轮新建实例换绑进 toolkit、{@link #reset()}
 * 开闸，轮内模型调用工具写入，轮结束后 {@link #consume()} 取走。同一循环内轮次串行执行，
 * AtomicReference 仅防御事件线程与控制线程间的可见性。</p>
 *
 * <p><b>接收闸 + 实例隔离</b>：超时/取消轮被 dispose 后，在途的 loop_report 工具线程可能
 * 仍会完成写入——runner 在失败出口 {@link #close()} 关闸后，迟到的写入被丢弃。闸门只能挡
 * 「同一实例上关闸之后」的写入，若写入晚于下一轮开跑才抵达，靠的是实例隔离：僵尸线程持有
 * 的是已关闸的旧轮实例，新一轮用的是全新实例，陈旧汇报无法被误当成新一轮的汇报消费
 * （假完成提议 / 幻影等待轮）。</p>
 */
public final class LoopReportTool {

    private static final Logger log = LoggerFactory.getLogger(LoopReportTool.class);

    /** 本轮汇报暂存；每轮 reset → 模型写入 → consume 取走。 */
    private final AtomicReference<LoopReport> current = new AtomicReference<>();

    /** 接收闸：true 时接受写入。reset() 开闸、close() 关闸（超时/取消轮的失败出口）。 */
    private volatile boolean accepting;

    /** 每轮开始前清空上一轮残留并开闸。 */
    public void reset() {
        current.set(null);
        accepting = true;
    }

    /** 关闸：本轮已被判失败/取消，此后迟到的写入（被 dispose 的在途工具线程）一律丢弃。 */
    public void close() {
        accepting = false;
        current.set(null);
    }

    /** 轮结束后取走本轮汇报；模型未调用工具时返回 null（下游降级到哨兵解析）。 */
    public LoopReport consume() {
        return current.getAndSet(null);
    }

    @Tool(name = LoopConstants.REPORT_TOOL_NAME,
            description = "【循环轮次汇报·每轮必须调用】你在自动循环里工作，每轮结束前必须调用本工具"
                    + "向循环系统汇报本轮结果，这是硬性协议。"
                    + "【字段纪律】"
                    + "1. done：只有确信全部成功准则都已真正满足才填 true——系统会独立核验（真跑命令、真查文件），"
                    + "谎报会被当场拆穿并浪费一轮；反之确实完成就果断填 true，不要无限打磨。"
                    + "2. remaining：未完成时必填，写清还差哪些、下一轮打算怎么做，要具体可执行；"
                    + "系统会逐轮比对，连续两轮 remaining 相同会被判原地打转并终止循环。"
                    + "3. next_delay_seconds：默认 0（立即开下一轮）；只有在等外部条件时才填正数"
                    + "（如等 CI 构建、等邮件送达后再查），并在 reason 里说明在等什么。"
                    + "等待轮不计入停滞，但受循环总时长上限约束，不要用等待逃避干活。"
                    + "4. 调用本工具后即可结束本轮回复。")
    public String report(
            @ToolParam(name = "done",
                    description = "目标是否已全部达成（会被独立核验，如实填写）") boolean done,
            @ToolParam(name = "summary",
                    description = "本轮做了什么的一两句简述。中文。") String summary,
            @ToolParam(name = "remaining", required = false,
                    description = "还差什么、下一轮打算怎么做；done=true 时可省略。中文。") String remaining,
            @ToolParam(name = "next_delay_seconds", required = false,
                    description = "建议的下轮延迟秒数：0=立即；等外部条件时填正数") Integer nextDelaySeconds,
            @ToolParam(name = "reason", required = false,
                    description = "next_delay_seconds>0 时必填：在等什么。中文。") String reason) {

        long delay = nextDelaySeconds == null ? 0L : nextDelaySeconds;
        LoopReport r = new LoopReport(done, summary, remaining, delay, reason);
        if (!accepting) {
            // 本轮已被判失败/取消（超时被 dispose 的在途调用迟到抵达）：丢弃，防污染下一轮
            log.warn("轮次汇报迟到（本轮已按失败/取消收束），已丢弃: done={} delay={}s", done, delay);
            return ToolResponse.success(LoopConstants.REPORT_TOOL_NAME, "汇报已收到。请结束本轮回复。");
        }
        current.set(r);
        log.info("收到轮次汇报: done={} delay={}s remaining=「{}」", done, delay,
                r.remaining().length() > 60 ? r.remaining().substring(0, 60) + "..." : r.remaining());
        return ToolResponse.success(LoopConstants.REPORT_TOOL_NAME,
                done ? "汇报已收到，系统将核验完成情况。请结束本轮回复。"
                     : "汇报已收到。请结束本轮回复，系统将安排下一轮。");
    }
}
