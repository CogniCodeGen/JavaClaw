package com.javaclaw.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code @loop} 指令解析测试：重点钉死单行写法——指令行上的非 key=value 词必须并入目标，
 * 不能静默丢弃（曾导致循环带着空目标启动、白烧满上限轮数）。
 */
class LoopDirectivesTest {

    @Test
    void 单行指令_参数后的词并入目标() {
        LoopDirectives d = LoopDirectives.parse("@loop max=5 盯着构建直到 mvn test 通过");
        assertEquals(5, d.maxIterations());
        assertEquals("盯着构建直到 mvn test 通过", d.goal());
    }

    @Test
    void 多行指令_首行参数与正文目标并存() {
        LoopDirectives d = LoopDirectives.parse("@loop interval=5m max=20 judge=on\n盯着构建，直到 mvn test 通过");
        assertEquals(300L, d.intervalSeconds());
        assertEquals(20, d.maxIterations());
        assertEquals(Boolean.TRUE, d.judge());
        assertEquals("盯着构建，直到 mvn test 通过", d.goal());
    }

    @Test
    void 单行词与正文目标同时存在_按先后拼接() {
        LoopDirectives d = LoopDirectives.parse("@loop max=3 盯着 CI\n绿了就通知我");
        assertEquals("盯着 CI\n绿了就通知我", d.goal());
    }

    @Test
    void 无指令行_整段输入即目标() {
        LoopDirectives d = LoopDirectives.parse("反复优化这段文案");
        assertEquals("反复优化这段文案", d.goal());
        assertEquals(-1L, d.intervalSeconds());
        assertEquals(-1, d.maxIterations());
        assertNull(d.judge());
    }

    @Test
    void 仅指令行无目标_目标为空由服务层拒绝启动() {
        LoopDirectives d = LoopDirectives.parse("@loop max=5");
        assertEquals("", d.goal());
    }

    @Test
    void 前缀需词边界_以loop开头的词不是指令() {
        // 回归缺陷：startsWith 命中后直接截断，「@loopback…」被从词中间劈开、目标遭静默篡改
        LoopDirectives d1 = LoopDirectives.parse("@loopback 服务恢复后通知我");
        assertEquals("@loopback 服务恢复后通知我", d1.goal());
        assertEquals(-1, d1.maxIterations());

        LoopDirectives d2 = LoopDirectives.parse("@loop间隔5分钟盯着CI");
        assertEquals("@loop间隔5分钟盯着CI", d2.goal());
    }

    @Test
    void 目标词含等号不被当未知键丢弃() {
        // 回归缺陷：targetSdk=34 这类含「=」的目标词被按「未知指令键」静默忽略，目标遭无声篡改
        LoopDirectives d = LoopDirectives.parse("@loop max=5 修复 targetSdk=34 的兼容问题");
        assertEquals(5, d.maxIterations());
        assertEquals("修复 targetSdk=34 的兼容问题", d.goal());
    }

    @Test
    void 目标正文里的已知键同形词不被当指令吃掉() {
        // 回归缺陷：单行解析在整行范围内消费已知 key=value，目标正文里的 max=3/max=10
        // 被吃成循环参数（后者生效），目标被篡改为「把配置里的 改成 并验证」
        LoopDirectives d = LoopDirectives.parse("@loop 把配置里的 max=3 改成 max=10 并验证");
        assertEquals(-1, d.maxIterations());
        assertEquals("把配置里的 max=3 改成 max=10 并验证", d.goal());

        // 行首前缀指令仍正常解析，前缀之后的同形词并入目标
        LoopDirectives d2 = LoopDirectives.parse("@loop max=5 把 interval=10 写进配置");
        assertEquals(5, d2.maxIterations());
        assertEquals(-1L, d2.intervalSeconds());
        assertEquals("把 interval=10 写进配置", d2.goal());
    }

    @Test
    void max值非法_标记为INVALID而非静默退默认() {
        // 回归缺陷：max=3x 解析失败静默退回配置默认 25 轮，预算键无声多烧数倍 token
        LoopDirectives d = LoopDirectives.parse("@loop max=3x 盯着构建");
        assertEquals(LoopDirectives.MAX_INVALID, d.maxIterations());
        assertEquals("盯着构建", d.goal());
    }

    @Test
    void interval支持min后缀() {
        LoopDirectives d = LoopDirectives.parse("@loop interval=5min 盯着CI");
        assertEquals(300L, d.intervalSeconds());
    }

    @Test
    void interval值非法_标记为INVALID而非静默降级自驱() {
        // 回归缺陷：解析失败返回 -1 与「未指定」混同，本意定时轮询被静默降级为零延迟自驱连发
        LoopDirectives d = LoopDirectives.parse("@loop interval=五分钟 盯着CI");
        assertEquals(LoopDirectives.INTERVAL_INVALID, d.intervalSeconds());
    }
}
