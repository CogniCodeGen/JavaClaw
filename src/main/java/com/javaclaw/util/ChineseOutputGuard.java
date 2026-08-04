package com.javaclaw.util;

import java.util.regex.Pattern;

/** 面向用户的模型自然语言输出闸门。提示词失效时以固定中文消息安全失败。 */
public final class ChineseOutputGuard {

    private static final String BLOCKED_MESSAGE =
            "模型返回了非中文自然语言内容，系统已阻止显示。请重试本次请求。";
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\r\n]+`");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern ENGLISH_SENTENCE = Pattern.compile(
            "(?i)(?:[a-z][a-z'’-]*[ ,;:]+){5,}[a-z][a-z'’-]*[.!?]?");

    private ChineseOutputGuard() {}

    /**
     * 保留中文解释中的代码、URL、协议字段和专有名词；纯英文或明显以英文段落为主时整段阻断。
     * 该方法必须在完整回复收集完毕后调用，不能逐 token 判断语言。
     */
    public static String enforceUserVisibleReply(String text) {
        String safe = SensitiveDataRedactor.redactText(text);
        if (safe == null || safe.isBlank()) return safe == null ? "" : safe;
        if (isArtifactOnly(safe.strip())) return safe;

        String prose = FENCED_CODE.matcher(safe).replaceAll(" ");
        prose = INLINE_CODE.matcher(prose).replaceAll(" ");
        prose = URL.matcher(prose).replaceAll(" ");
        int han = 0;
        int latin = 0;
        for (int i = 0; i < prose.length(); i++) {
            char value = prose.charAt(i);
            if (isHan(value)) han++;
            else if ((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')) latin++;
        }
        if (latin > 0 && han == 0) return BLOCKED_MESSAGE;
        if (latin > Math.max(80, han * 8) && ENGLISH_SENTENCE.matcher(prose).find()) {
            return BLOCKED_MESSAGE;
        }
        return safe;
    }

    static String blockedMessage() {
        return BLOCKED_MESSAGE;
    }

    private static boolean isArtifactOnly(String value) {
        if (value.startsWith("```") && value.endsWith("```")) return true;
        if ((value.startsWith("{") && value.endsWith("}"))
                || (value.startsWith("[") && value.endsWith("]"))) return true;
        if (value.matches("https?://\\S+")) return true;
        if (!value.contains(" ") && (value.startsWith("/") || value.matches("[A-Za-z]:[\\\\/].+"))) {
            return true;
        }
        // 无围栏的短代码/协议片段：出现结构符且不像自然语言句子。
        return value.length() < 500 && (value.contains("{") || value.contains(";") || value.contains("=>"))
                && !ENGLISH_SENTENCE.matcher(value).find();
    }

    private static boolean isHan(char value) {
        Character.UnicodeScript script = Character.UnicodeScript.of(value);
        return script == Character.UnicodeScript.HAN;
    }
}
