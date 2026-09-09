package com.javaclaw.server.persistence;

import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadTitleTest {
    @Test
    void 提取Markdown开头并合并空白而保留实际文字() {
        assertEquals(Optional.of("修复 输入卡顿 请分析原因"), ThreadTitle.fromMessage("\n## **修复 输入卡顿**\n\t请分析原因\u00a0"));
        assertEquals(Optional.of("添加回车发送"), ThreadTitle.fromMessage("- [x] 添加回车发送"));
        assertEquals(Optional.of("检查配置"), ThreadTitle.fromMessage("> 1. 检查`配置`"));
        assertEquals(
                Optional.of("System.out.println(1);"), ThreadTitle.fromMessage("```java\nSystem.out.println(1);\n```"));
    }

    @Test
    void 短标题不加省略号且超长中文最多三十二个完整字符() {
        assertEquals(Optional.of("字".repeat(32)), ThreadTitle.fromMessage("字".repeat(32)));
        assertEquals(Optional.of("字".repeat(31) + "…"), ThreadTitle.fromMessage("字".repeat(33)));
        assertTrue(ThreadTitle.fromMessage(" \n\t\u3000").isEmpty());
        assertTrue(ThreadTitle.fromMessage("## \n- [ ] ").isEmpty());
    }

    @Test
    void 组合Emoji和音标按完整grapheme截断且异常长组合不越过存储上限() {
        for (String grapheme : new String[] {"👨‍👩‍👧‍👦", "👍🏽", "🇨🇳", "1️⃣", "a\u0301"}) {
            String title = ThreadTitle.fromMessage(grapheme.repeat(33)).orElseThrow();
            assertEquals(grapheme.repeat(31) + "…", title);
            assertEquals(32, Pattern.compile("\\X").matcher(title).results().count());
        }
        assertTrue(ThreadTitle.fromMessage("a" + "\u0301".repeat(600)).isEmpty());
    }
}
