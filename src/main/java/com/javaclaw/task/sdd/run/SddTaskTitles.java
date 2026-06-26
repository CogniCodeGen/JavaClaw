package com.javaclaw.task.sdd.run;

import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.prompt.SddPrompts;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * 托管任务标题生成器 —— 标题留空时用轻量模型从需求描述生成一句简洁标题。
 *
 * <p>仿 {@code GoalManager} / {@code ToolRouter} 范式：单次轻量模型调用 + 硬超时，
 * 任何失败/超时/空结果都<b>静默回退</b>为截断描述前若干字，绝不抛异常、绝不阻断任务创建。</p>
 *
 * <p>注意：本类做模型调用，<b>不要</b>在 {@code SddTaskManager.create()}（synchronized）或
 * JavaFX 应用线程里直接调用——调用方应在后台线程先生成好标题再下传。</p>
 */
public final class SddTaskTitles {

    private static final Logger log = LoggerFactory.getLogger(SddTaskTitles.class);

    /** 标题生成硬超时：标题非关键路径，不值得久等 */
    private static final Duration GEN_TIMEOUT = Duration.ofSeconds(10);

    /** 回退截断长度（字符） */
    private static final int FALLBACK_LEN = 24;

    private SddTaskTitles() {}

    /**
     * 为描述生成简洁标题；失败/超时回退为截断描述。
     *
     * @param modelFactory 模型工厂（取轻量模型）；为空则直接回退截断
     * @param description  需求描述
     * @return 简洁标题（始终非空，除非描述本身为空则返回"未命名任务"）
     */
    public static String generate(ModelFactory modelFactory, String description) {
        String desc = description == null ? "" : description.trim();
        if (desc.isEmpty()) return "未命名任务";
        if (modelFactory == null) return fallback(desc);
        try {
            ChatModelBase model = modelFactory.createLightChatModel();
            Msg sysMsg = Msg.builder().role(MsgRole.SYSTEM).name("system").textContent(SddPrompts.TITLE_SYS_PROMPT).build();
            Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(desc).build();

            StringBuilder sb = new StringBuilder();
            List<ChatResponse> responses = model.stream(
                    List.of(sysMsg, userMsg), List.of(), GenerateOptions.builder().build()
            ).collectList().block(GEN_TIMEOUT);

            if (responses != null) {
                for (ChatResponse resp : responses) {
                    if (resp.getContent() != null) {
                        for (ContentBlock block : resp.getContent()) {
                            if (block instanceof TextBlock tb && tb.getText() != null) {
                                sb.append(tb.getText());
                            }
                        }
                    }
                }
            }
            String title = clean(sb.toString());
            return title.isEmpty() ? fallback(desc) : title;
        } catch (Exception e) {
            log.warn("[SDD] 标题生成失败，回退截断: {}", e.getMessage());
            return fallback(desc);
        }
    }

    /** 清洗模型输出：去首尾空白/引号/换行，限长 30 字防失控 */
    private static String clean(String raw) {
        if (raw == null) return "";
        String t = raw.trim().replaceAll("^[\"'《「\\s]+|[\"'》」\\s]+$", "");
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        if (t.length() > 30) t = t.substring(0, 30);
        return t;
    }

    private static String fallback(String desc) {
        String d = desc.replaceAll("\\s+", " ").trim();
        return d.length() > FALLBACK_LEN ? d.substring(0, FALLBACK_LEN) + "…" : d;
    }
}
