package com.javaclaw.memory.curation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Distiller#parseIndexes} 取代判定编号解析测试 ——
 * 核心钉死「误删优先保守」：软删除不可逆，判定文本一旦掺杂编号以外的文字（复述事实、
 * 版本号、解释、「取代」字样），必须<b>整体跳过</b>，绝不把无关数字当编号删掉正确事实（评审 #1）。
 *
 * @author JavaClaw
 */
class DistillerParseIndexesTest {

    @Test
    void 纯编号与分隔符变体_正常解析为0基下标() {
        assertEquals(List.of(0), Distiller.parseIndexes("1", 5));
        assertEquals(List.of(0, 2), Distiller.parseIndexes("1,3", 5));
        assertEquals(List.of(0, 2), Distiller.parseIndexes("1、3", 5));
        assertEquals(List.of(0, 2), Distiller.parseIndexes("1 和 3", 5));
        assertEquals(List.of(0, 2), Distiller.parseIndexes("1，3", 5));
    }

    @Test
    void 允许前缀标签与尾随标点() {
        assertEquals(List.of(0, 2), Distiller.parseIndexes("输出：1,3", 5));
        assertEquals(List.of(0), Distiller.parseIndexes("取代: 1", 5));
        assertEquals(List.of(0, 2), Distiller.parseIndexes("1,3。", 5));
    }

    @Test
    void 掺杂文字的判定输出_整体保守跳过_杜绝误删() {
        // 复述新事实 + 版本号 → 绝不把 "9"/"1" 当编号删掉无关事实
        assertTrue(Distiller.parseIndexes("新事实 v9 取代 1", 9).isEmpty());
        // 解释性文字
        assertTrue(Distiller.parseIndexes("第1条已过时，因为用户换了工具", 5).isEmpty());
        // 夹带字母
        assertTrue(Distiller.parseIndexes("1 and 3", 5).isEmpty());
    }

    @Test
    void 越界编号丢弃_去重保序() {
        assertTrue(Distiller.parseIndexes("9", 5).isEmpty());   // 越界
        assertEquals(List.of(0), Distiller.parseIndexes("1,1", 5)); // 去重
        assertEquals(List.of(2, 0), Distiller.parseIndexes("3,1", 5)); // 保序（按输出顺序）
    }

    @Test
    void 空与null输入返回空() {
        assertTrue(Distiller.parseIndexes(null, 5).isEmpty());
        assertTrue(Distiller.parseIndexes("", 5).isEmpty());
        assertTrue(Distiller.parseIndexes("   ", 5).isEmpty());
    }
}
