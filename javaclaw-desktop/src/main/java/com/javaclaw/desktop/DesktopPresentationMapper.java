package com.javaclaw.desktop;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** SDK 领域值到中文展示文本的唯一转换入口；不得在此改变领域状态或权限语义。 */
final class DesktopPresentationMapper {
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern(
                    "MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.systemDefault());

    private DesktopPresentationMapper() {}

    /** 将后端状态转换为稳定中文文案；未知值保留原文，便于前向兼容。 */
    static String status(String value) {
        if (value == null || value.isBlank()) {
            return "暂无状态";
        }
        return switch (value) {
            case "ACTIVE", "RUNNING" -> "运行中";
            case "COMPLETED", "SUCCEEDED", "HEALTHY" -> "已完成";
            case "FAILED", "UNHEALTHY" -> "失败";
            case "INTERRUPTED", "CANCELLED" -> "已中断";
            case "DRAFT" -> "草稿";
            case "ENABLED" -> "已启用";
            case "DISABLED" -> "已停用";
            case "QUARANTINED" -> "已隔离";
            case "DEGRADED_EMBEDDING_UNAVAILABLE" -> "向量检索未配置，当前使用关键词检索";
            case "KEYWORD_ONLY" -> "关键词检索";
            case "INDEXED", "READY" -> "可用";
            case "PENDING" -> "等待处理";
            case "UNKNOWN" -> "结果待确认";
            default -> humanize(value);
        };
    }

    /** 显示可空时间；不存在时使用调用方给出的业务文案。 */
    static String instant(Instant value, String absent) {
        return value == null ? absent : SHORT_TIME.format(value);
    }

    /** 将会话更新时间压缩为旧侧栏使用的短时间，避免日期文本挤占标题宽度。 */
    static String sidebarTime(Instant value) {
        return sidebarTime(value, LocalDate.now(), ZoneId.systemDefault());
    }

    static String sidebarTime(Instant value, LocalDate today, ZoneId zone) {
        if (value == null) {
            return "";
        }
        var dateTime = value.atZone(zone);
        LocalDate date = dateTime.toLocalDate();
        if (date.equals(today)) {
            return String.format(Locale.ROOT, "%02d:%02d", dateTime.getHour(), dateTime.getMinute());
        }
        if (date.equals(today.minusDays(1))) {
            return "昨天";
        }
        if (date.isAfter(today.minusDays(7))) {
            return switch (date.getDayOfWeek()) {
                case MONDAY -> "周一";
                case TUESDAY -> "周二";
                case WEDNESDAY -> "周三";
                case THURSDAY -> "周四";
                case FRIDAY -> "周五";
                case SATURDAY -> "周六";
                case SUNDAY -> "周日";
            };
        }
        return String.format(Locale.ROOT, "%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
    }

    /** 将服务端标题压缩为侧栏可安全显示的单行文本，避免换行撑高虚拟化会话行。 */
    static String sidebarTitle(String value) {
        if (value == null || value.isBlank()) {
            return "新对话";
        }
        return value.strip().replaceAll("\\s+", " ");
    }

    /** 将字节数转换为短中文容量，避免管理页直接展示难读的大整数。 */
    static String bytes(long value) {
        long safe = Math.max(0, value);
        if (safe < 1024) {
            return safe + " B";
        }
        if (safe < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", safe / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", safe / (1024.0 * 1024.0));
    }

    /** 将规则路径优先显示为 Workspace 相对路径；越界或不可比较时只显示文件名。 */
    static String workspacePath(Path workspaceRoot, Path value) {
        if (value == null) {
            return "未知路径";
        }
        Path normalized = value.toAbsolutePath().normalize();
        if (workspaceRoot != null) {
            Path root = workspaceRoot.toAbsolutePath().normalize();
            if (normalized.startsWith(root)) {
                Path relative = root.relativize(normalized);
                return relative.getNameCount() == 0 ? "." : relative.toString();
            }
        }
        Path name = normalized.getFileName();
        return name == null ? "外部规则文件" : name.toString();
    }

    /** 缩短非敏感内容摘要；完整值仅应在显式详情中使用。 */
    static String shortHash(String value) {
        if (value == null || value.isBlank()) {
            return "无摘要";
        }
        return value.substring(0, Math.min(12, value.length()));
    }

    /** 显示可空文本，避免 {@code null} 或空串进入界面。 */
    static String text(String value, String absent) {
        return value == null || value.isBlank() ? absent : value;
    }

    /** 将公开 Profile kind 转换为产品文案。 */
    static String profileKind(String value) {
        return switch (text(value, "")) {
            case "CHAT" -> "对话";
            case "PLAN" -> "规划";
            case "LOOP" -> "循环任务";
            case "WORKFLOW" -> "工作流";
            case "SDD" -> "规格驱动开发";
            case "SCHEDULE" -> "定时任务";
            case "SUBAGENT" -> "子智能体";
            default -> status(value);
        };
    }

    /** 将云 Provider 标识转换为稳定品牌名。 */
    static String provider(String value) {
        return switch (text(value, "").toLowerCase(Locale.ROOT)) {
            case "openai" -> "OpenAI";
            case "anthropic" -> "Anthropic";
            case "google" -> "Google";
            default -> text(value, "未选择 Provider");
        };
    }

    /** 将沙箱策略转换为不泄漏枚举的权限说明。 */
    static String sandbox(String value) {
        return switch (text(value, "")) {
            case "READ_ONLY" -> "只读";
            case "WORKSPACE_WRITE" -> "工作区可写";
            case "HOST_FULL_ACCESS" -> "宿主完全访问（逐次审批）";
            default -> status(value);
        };
    }

    /** 将布尔状态转换为明确中文，避免无上下文的 true/false。 */
    static String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    /** 将公共技术枚举转换为空格分隔文本，不将内部下划线直接泄漏到界面。 */
    static String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "暂无";
        }
        String normalized = value.strip().replace('_', ' ').toLowerCase(Locale.ROOT);
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }
}
