package com.javaclaw.desktop;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DesktopPresentationMapperTest {
    @TempDir
    Path temporary;

    @Test
    void nullableAndBackendValuesBecomeStableChinesePresentationText() {
        assertEquals("暂无状态", DesktopPresentationMapper.status(null));
        assertEquals("向量检索未配置，当前使用关键词检索", DesktopPresentationMapper.status("DEGRADED_EMBEDDING_UNAVAILABLE"));
        assertEquals("待计算", DesktopPresentationMapper.instant(null, "待计算"));
        assertEquals("未提供", DesktopPresentationMapper.text(null, "未提供"));
        assertEquals("规划", DesktopPresentationMapper.profileKind("PLAN"));
        assertEquals("工作区可写", DesktopPresentationMapper.sandbox("WORKSPACE_WRITE"));
        assertEquals("Anthropic", DesktopPresentationMapper.provider("anthropic"));
        assertFalse(DesktopPresentationMapper.instant(Instant.now(), "待计算").contains("null"));
    }

    @Test
    void projectInstructionPathsPreferWorkspaceRelativeNamesAndBoundedMetadata() {
        Path workspace = temporary.resolve("workspace");
        assertEquals(
                Path.of("src", "AGENTS.md").toString(),
                DesktopPresentationMapper.workspacePath(workspace, workspace.resolve("src/AGENTS.md")));
        assertEquals(
                "AGENTS.md", DesktopPresentationMapper.workspacePath(workspace, temporary.resolve("global/AGENTS.md")));
        assertEquals("1.5 KiB", DesktopPresentationMapper.bytes(1536));
        assertEquals("0123456789ab", DesktopPresentationMapper.shortHash("0123456789abcdef"));
    }

    @Test
    void sidebarTimeUsesTheCompactLegacyConversationFormat() {
        LocalDate today = LocalDate.of(2026, 8, 30);
        ZoneId zone = ZoneId.of("UTC");

        assertEquals(
                "19:26", DesktopPresentationMapper.sidebarTime(Instant.parse("2026-08-30T19:26:00Z"), today, zone));
        assertEquals("昨天", DesktopPresentationMapper.sidebarTime(Instant.parse("2026-08-29T12:00:00Z"), today, zone));
        assertEquals("周三", DesktopPresentationMapper.sidebarTime(Instant.parse("2026-08-26T12:00:00Z"), today, zone));
        assertEquals(
                "08-01", DesktopPresentationMapper.sidebarTime(Instant.parse("2026-08-01T12:00:00Z"), today, zone));
        assertEquals("", DesktopPresentationMapper.sidebarTime(null, today, zone));
    }

    @Test
    void sidebarTitleAlwaysUsesOneCompactLine() {
        assertEquals("新对话", DesktopPresentationMapper.sidebarTitle(null));
        assertEquals("新对话", DesktopPresentationMapper.sidebarTitle(" \n\t "));
        assertEquals("你好 世界", DesktopPresentationMapper.sidebarTitle("  你好\n\t世界  "));
    }
}
