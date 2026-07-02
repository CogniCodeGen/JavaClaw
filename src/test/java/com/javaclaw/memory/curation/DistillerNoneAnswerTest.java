package com.javaclaw.memory.curation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Distiller#isNoneAnswer} 判「无」归一化测试 ——
 * 覆盖模型常见变体（标点尾缀 / 列表前缀 / 英文 none / 整句否定），
 * 防止「无。」之类回答被当成事实落库。
 *
 * @author JavaClaw
 */
class DistillerNoneAnswerTest {

    @Test
    void 标准与变体的无回答均应命中() {
        assertTrue(Distiller.isNoneAnswer("无"));
        assertTrue(Distiller.isNoneAnswer("无。"));
        assertTrue(Distiller.isNoneAnswer("- 无"));
        assertTrue(Distiller.isNoneAnswer("- 无。"));
        assertTrue(Distiller.isNoneAnswer("没有"));
        assertTrue(Distiller.isNoneAnswer("None"));
        assertTrue(Distiller.isNoneAnswer("none."));
        assertTrue(Distiller.isNoneAnswer("没有值得记录的事实"));
        assertTrue(Distiller.isNoneAnswer("没有值得记忆的事实。"));
        assertTrue(Distiller.isNoneAnswer("没有可抽取的实体"));
        assertTrue(Distiller.isNoneAnswer(""));
        assertTrue(Distiller.isNoneAnswer("   "));
        assertTrue(Distiller.isNoneAnswer(null));
    }

    @Test
    void 真实事实不应被误判为无() {
        assertFalse(Distiller.isNoneAnswer("用户偏好 Python 3.12"));
        assertFalse(Distiller.isNoneAnswer("- 用户无咖啡因不耐受")); // 含「无」但非否定回答
        assertFalse(Distiller.isNoneAnswer("项目无外部依赖，纯 Java 实现"));
        assertFalse(Distiller.isNoneAnswer("无线网络是用户的研究方向"));
    }
}
