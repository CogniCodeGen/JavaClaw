package com.javaclaw.agent.clarify;

import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型主动发起的澄清请求工具集
 *
 * <p>当模型确认用户输入存在歧义或重要缺失、且模型不应代替用户做决策时，
 * 调用 {@link #askUserClarification(String, String)} 工具向用户提问。
 * 工具会通过 {@link ConversationCallbacks} 投递一个 {@code Custom("clarify_request")} 事件，
 * 由 UI 渲染澄清卡片；同时工具的返回值要求模型立即结束本轮回复，
 * 等待用户的下一条输入。</p>
 *
 * <p>线程模型：每次 {@code streamChat} 调用前由编排器绑定本轮的回调，
 * 调用结束在 doFinally 解绑。绑定/解绑通过 CAS（{@link AtomicReference}）原子完成，
 * 不同轮的回调不会相互覆盖。</p>
 *
 * @author JavaClaw
 */
public final class ClarifyTools {

    private static final Logger log = LoggerFactory.getLogger(ClarifyTools.class);

    /**
     * 本轮绑定：UI 回调 + 中断器（dispose 订阅）。
     * 用单个 AtomicReference 持有，确保 set / compareAndSet 原子地一并替换，跨轮不混淆。
     */
    private record Binding(ConversationCallbacks callbacks, Runnable interrupter) {}

    private final AtomicReference<Binding> activeBinding = new AtomicReference<>();

    /**
     * 绑定本轮回调与中断器；返回的句柄用于 {@link #unbind(Object)} 仅在仍持有时清空。
     *
     * @param callbacks  UI 事件回调
     * @param interrupter 中断器：调用后会立即取消编排器的活跃订阅（如 {@code activeSubscription::dispose}）
     */
    public Object bind(ConversationCallbacks callbacks, Runnable interrupter) {
        Binding b = new Binding(callbacks, interrupter);
        activeBinding.set(b);
        return b;
    }

    /**
     * 解绑，仅在当前持有的就是传入句柄时才置空，避免跨轮误解绑。
     */
    public void unbind(Object handle) {
        if (handle instanceof Binding b) {
            activeBinding.compareAndSet(b, null);
        }
    }

    @Tool(name = "ask_user_clarification",
            description = "【中断本轮，询问用户】当且仅当用户输入存在你无法替其决策的歧义或重要缺失时调用本工具，" +
                    "向用户提问让其修正，并主动停止本轮回复。" +
                    "适用场景示例：" +
                    "① 用户说\"帮我把文件转码\"但未指定源/目标编码；" +
                    "② 用户说\"删除旧版本\"但未指定版本号或路径；" +
                    "③ 多个候选方案在用户偏好维度（性能 vs 体积、安全 vs 易用）必须由人来选；" +
                    "④ 任务涉及用户私有信息（账号、邮箱、收件人）但用户未提供。" +
                    "【严格约束】" +
                    "1. 只在确实无法基于上下文与常识推断时调用；能用合理默认值时绝不调用。" +
                    "2. 调用本工具后你必须立即结束本轮回复，不要再做任何推理、生成或工具调用，等待用户的下一条输入。" +
                    "3. reason 字段必须用中文向用户说明你为什么问这个问题（识别出的歧义/缺失是什么、为什么模型不能替决策）。" +
                    "4. question 字段必须是具体可回答的问题；若有候选项请列出供用户选择，避免开放式提问。")
    public String askUserClarification(
            @ToolParam(name = "reason",
                    description = "向用户解释为什么需要澄清：识别出的歧义/缺失是什么、为什么模型不能替决策。中文。") String reason,
            @ToolParam(name = "question",
                    description = "向用户的具体提问；若有候选选项请逐条列出。中文。") String question) {

        String safeReason = reason == null ? "" : reason.trim();
        String safeQuestion = question == null ? "" : question.trim();

        if (safeReason.isEmpty() && safeQuestion.isEmpty()) {
            return ToolResponse.error("ask_user_clarification",
                    "reason 与 question 均为空，澄清请求无效。请重新组织你的澄清问题。");
        }

        log.info("模型主动请求澄清: reason=「{}」 question=「{}」", safeReason, safeQuestion);

        Binding b = activeBinding.get();
        if (b == null) {
            log.warn("澄清通道未绑定，澄清请求无法投递到 UI（模型仍应停止本轮）");
            return ToolResponse.error("ask_user_clarification",
                    "澄清通道未就绪，无法投递问题给用户。请直接以纯文本向用户说明你需要澄清的内容。");
        }

        try {
            b.callbacks().onEvent(new ConversationEvent.Custom(
                    "clarify_request", new ClarifyPayload(safeReason, safeQuestion)));
        } catch (Exception e) {
            log.error("投递澄清事件失败", e);
            return ToolResponse.error("ask_user_clarification",
                    "澄清事件投递失败: " + e.getMessage());
        }

        // 强制中断：不再"求"模型停，由本工具直接 dispose 编排器订阅。
        // 即便模型不遵守"立即结束"的指令继续生成，下游也已无订阅者，节省 token 并避免误执行。
        try {
            if (b.interrupter() != null) b.interrupter().run();
            log.info("已主动中断编排器流式订阅");
        } catch (Exception e) {
            log.warn("调用中断器失败（忽略，UI 侧仍有兜底）", e);
        }

        return ToolResponse.success("ask_user_clarification",
                "已向用户展示澄清请求并强制结束本轮对话。" +
                "等待用户阅读问题后输入修正信息，下一轮可继续。");
    }
}
