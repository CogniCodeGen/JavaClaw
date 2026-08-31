package com.javaclaw.agent.knowledge;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 可溯源低风险记忆规则；模型输出是候选，不能决定是否覆盖固定内容或敏感人设。 */
public final class MemorySafety {
    private static final Pattern SECRET = Pattern.compile(
            "(?is)(?:password|passwd|api[_-]?key|client[_-]?secret|authorization|access[_-]?token|refresh[_-]?token|密码|密钥)\\s*[:=：]|(?:sk-[A-Za-z0-9_-]{16,})|-----BEGIN [A-Z ]*PRIVATE KEY-----");

    private MemorySafety() {}

    /** 拒绝明显凭据；不能以脱敏后悄悄保存不同事实替代用户输入。 */
    public static void requireNoSecrets(String content) {
        if (SECRET.matcher(content).find()) {
            throw new IllegalArgumentException("memory content may contain credentials; use SecretStore instead");
        }
    }

    /** 自动写入只允许可逐字核验的低风险事实；问题、推测、Persona、覆盖及固定内容始终进入提案审批。 */
    public static boolean mayAutomaticallyAccept(
            MemoryRepository.MemoryDraft draft, MemoryRepository.MemoryDocument existing, List<String> evidence) {
        if (existing != null
                || draft.pinned()
                || !"FACT".equalsIgnoreCase(draft.kind())
                || draft.sourceItemIds().isEmpty()
                || draft.content().length() > 1_000
                || draft.content()
                        .matches(
                                "(?is).*(?:疾病|诊断|病史|政治|宗教|性取向|身份证|护照|银行卡|工资|收入|住址|电话|邮箱|diagnosis|religion|sexual|passport|salary|social.security).*")
                || !Set.of("language", "format", "timezone", "project", "语言", "格式", "时区", "项目")
                        .contains(draft.attribute().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return evidence.stream()
                .anyMatch(text -> !text.contains("?")
                        && !text.contains("？")
                        && text.contains(draft.content())
                        && !text.matches("(?s).*(?:可能|也许|假设|如果|might|perhaps).*"));
    }
}
