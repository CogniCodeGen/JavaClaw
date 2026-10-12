package com.javaclaw.task.sdd.run;

/**
 * 托管任务标题回退生成器。模型标题能力由应用层通过 ModelTaskGateway 提供；本类只保留
 * 无网络、可确定性复用的清洗和回退规则。
 *
 * <p>通过统一 ModelTaskGateway 执行单次轻量模型调用并受 Run 超时约束，
 * 任何失败/超时/空结果都<b>静默回退</b>为截断描述前若干字，绝不抛异常、绝不阻断任务创建。</p>
 *
 * <p>注意：本类做模型调用，<b>不要</b>在 {@code SddTaskManager.create()}（synchronized）或
 * JavaFX 应用线程里直接调用——调用方应在后台线程先生成好标题再下传。</p>
 */
public final class SddTaskTitles {

    /** 回退截断长度（字符） */
    private static final int FALLBACK_LEN = 24;

    private SddTaskTitles() {}

    /** 清洗模型输出：去首尾空白/引号/换行，限长 30 字防失控 */
    private static String clean(String raw) {
        if (raw == null) return "";
        String t = raw.trim().replaceAll("^[\"'《「\\s]+|[\"'》」\\s]+$", "");
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        if (t.length() > 30) t = t.substring(0, 30);
        return t;
    }

    public static String fallback(String desc) {
        if (desc == null || desc.isBlank()) return "未命名任务";
        String d = desc.replaceAll("\\s+", " ").trim();
        return d.length() > FALLBACK_LEN ? d.substring(0, FALLBACK_LEN) + "…" : d;
    }
}
