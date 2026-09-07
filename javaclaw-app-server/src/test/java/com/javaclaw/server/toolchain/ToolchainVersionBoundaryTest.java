package com.javaclaw.server.toolchain;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证项目声明常用的范围端点，特别是 npm 部分版本与 Python 补零相等的差异。 */
class ToolchainVersionBoundaryTest {
    @Test
    void 连字符范围包含完整端点而部分上界包含该次版本系列() {
        assertTrue(ToolchainVersionConstraint.matches("22.18.0", "22.18.0 - 22.19.1"));
        assertTrue(ToolchainVersionConstraint.matches("22.19.1", "22.18.0 - 22.19.1"));
        assertFalse(ToolchainVersionConstraint.matches("22.19.2", "22.18.0 - 22.19.1"));
        assertTrue(ToolchainVersionConstraint.matches("22.19.99", "22.18 - 22.19"));
        assertFalse(ToolchainVersionConstraint.matches("22.20.0", "22.18 - 22.19"));
    }

    @Test
    void 完整版本比较严格区分等号和相邻补丁() {
        assertFalse(ToolchainVersionConstraint.matches("22.18.0", ">22.18.0"));
        assertTrue(ToolchainVersionConstraint.matches("22.18.1", "> 22.18.0"));
        assertTrue(ToolchainVersionConstraint.matches("22.18.0", "<=22.18.0"));
        assertFalse(ToolchainVersionConstraint.matches("22.18.1", "<=22.18.0"));
        assertTrue(ToolchainVersionConstraint.matches("22.19.0", ">22.18"));
        assertFalse(ToolchainVersionConstraint.matches("22.18.99", ">22.18"));
    }

    @Test
    void Python显式相等补零而尾部通配允许整个补丁系列() {
        assertTrue(ToolchainVersionConstraint.matches("3.12.0", "==3.12", true));
        assertFalse(ToolchainVersionConstraint.matches("3.12.11", "==3.12", true));
        assertTrue(ToolchainVersionConstraint.matches("3.12.11", "==3.12.*", true));
        assertFalse(ToolchainVersionConstraint.matches("3.12.11", "!=3.12.*", true));
        assertTrue(ToolchainVersionConstraint.matches("3.13.0", "!=3.12.*", true));
        assertTrue(ToolchainVersionConstraint.matches("3.12.11", ">3.12", true));
        assertFalse(ToolchainVersionConstraint.matches("3.12.0", ">3.12", true));
    }

    @Test
    void 零主版本caret与Python兼容发布不越过各自语义上界() {
        assertTrue(ToolchainVersionConstraint.matches("0.2.9", "^0.2.3"));
        assertFalse(ToolchainVersionConstraint.matches("0.3.0", "^0.2.3"));
        assertTrue(ToolchainVersionConstraint.matches("0.0.3", "^0.0.3"));
        assertFalse(ToolchainVersionConstraint.matches("0.0.4", "^0.0.3"));
        assertTrue(ToolchainVersionConstraint.matches("3.12.99", "~=3.12.1", true));
        assertFalse(ToolchainVersionConstraint.matches("3.13.0", "~=3.12.1", true));
        assertFalse(ToolchainVersionConstraint.matches("3.12.0", "~=3.12.1", true));
    }

    @Test
    void 已满足或已矛盾的子句均不能掩盖后续语法错误() {
        for (String invalid : List.of("", " ", "1".repeat(201), ">=0 || ", "<0 not-a-version")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ToolchainVersionConstraint.matches("22.18.0", invalid),
                    invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> ToolchainVersionConstraint.matches("lts/iron", ">=20"));
    }
}
