package com.javaclaw.loop.agent;

import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.loop.LoopConstants;
import com.javaclaw.loop.model.LoopReport;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 循环轮次元数据工具：执行体可提交进度摘要和下一轮延迟。
 *
 * <p>用独立<b>工具调用</b>提交可选元数据，结构化参数没有解析歧义，并承载「自报下轮延迟」（声明式等待，
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
 * （幻影等待轮）。</p>
 */
@com.javaclaw.framework.spi.ToolContract(group = "dynamic_task", permissions = {"tool.execute"}, idempotent = false)
public final class LoopReportTool {

    private static final Logger log = LoggerFactory.getLogger(LoopReportTool.class);

    /** 本轮汇报暂存；每轮 reset → 模型写入 → consume 取走。 */
    private final AtomicReference<LoopReport> current = new AtomicReference<>();

    /** 接收闸：true 时接受写入。reset() 开闸、close() 关闸（超时/取消轮的失败出口）。 */
    private volatile boolean accepting;

    public LoopReportTool() {
        reset();
    }

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

    /** 轮结束后取走可选元数据；模型未调用工具时返回 null。 */
    public LoopReport consume() {
        return current.getAndSet(null);
    }

    @Tool(name = LoopConstants.REPORT_TOOL_NAME,
            description = "【循环轮次可选元数据】本工具只记录进度摘要及下轮延迟，"
                    + "不能提交完成结论；完成、继续或受阻必须通过 harness_submit_decision 独立提交。"
                    + "remaining 可写下一轮打算做什么，只供展示和接力，不作状态判断。"
                    + "nextDelaySeconds 默认 0（立即开下一轮）；只有在等外部条件时才填正数"
                    + "（如等 CI 构建、等邮件送达后再查），并在 reason 里说明在等什么。"
                    + "等待轮不计入停滞，但受循环总时长上限约束。"
                    + "提交本工具后，仍须单独调用 harness_submit_decision。")
    public String report(
            @ToolParam(
                    description = "本轮做了什么的一两句简述，跟随用户语言。") String summary,
            @ToolParam( required = false,
                    description = "还差什么、下一轮打算怎么做；只供展示。跟随用户语言。") String remaining,
            @ToolParam( required = false,
                    description = "建议的下轮延迟秒数：0=立即；等外部条件时填正数") Integer nextDelaySeconds,
            @ToolParam( required = false,
                    description = "nextDelaySeconds>0 时必填：在等什么。跟随用户语言。") String reason) {

        long delay = nextDelaySeconds == null ? 0L : nextDelaySeconds;
        LoopReport r = new LoopReport(summary, remaining, delay, reason);
        if (!accepting) {
            // 本轮已被判失败/取消（超时被 dispose 的在途调用迟到抵达）：丢弃，防污染下一轮
            log.warn("轮次汇报迟到（本轮已按失败/取消收束），已丢弃: delay={}s", delay);
            return ToolResponse.error(LoopConstants.REPORT_TOOL_NAME, "本轮已结束，迟到的汇报未被接收。");
        }
        current.set(r);
        log.info("收到轮次汇报: delay={}s remaining=「{}」", delay,
                r.remaining().length() > 60 ? r.remaining().substring(0, 60) + "..." : r.remaining());
        return ToolResponse.success(LoopConstants.REPORT_TOOL_NAME,
                "轮次元数据已收到。请通过 harness_submit_decision 提交本轮决策。");
    }
}
