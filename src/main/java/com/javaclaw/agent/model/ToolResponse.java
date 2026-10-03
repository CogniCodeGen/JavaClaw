package com.javaclaw.agent.model;

import com.javaclaw.framework.spi.ToolEffectCapture;

/**
 * 面向用户和模型的旧式工具展示文案。
 *
 * <p>执行状态由 {@link ToolEffectCapture} 独立采集，并由调用网关写入
 * 结构化 ToolExecutionResultV1；返回字符串绝不可用于控制流或效果验收。</p>
 *
 * <p>输出格式示例：
 * <pre>
 * [web_navigate][成功] 已导航到: https://www.baidu.com
 * [web_click][失败] 未找到元素 [5]
 * [web_execute_task][超时] 任务在 30 秒内未完成，请检查页面状态后重试
 * [email_send][失败] 连接邮件服务器超时
 * </pre>
 * </p>
 *
 * @author JavaClaw
 */
public final class ToolResponse {

    private ToolResponse() {
        // 工具类不可实例化
    }

    /**
     * 构建成功响应
     *
     * @param toolName 工具名称
     * @param message  结果描述
     * @return 格式化的成功响应
     */
    public static String success(String toolName, String message) {
        return format(toolName, ToolEffectCapture.Signal.SUCCESS, message);
    }

    /**
     * 构建失败响应
     *
     * @param toolName 工具名称
     * @param message  错误描述
     * @return 格式化的失败响应
     */
    public static String error(String toolName, String message) {
        return format(toolName, ToolEffectCapture.Signal.ERROR, message);
    }

    /**
     * 构建“请求已受理但业务变更尚未生效”的响应。
     *
     * <p>典型场景是技能变更提案：入队成功不等于技能已经落盘。独立状态可防止模型把
     * {@code [成功] 提案已提交} 误读成“目标对象已创建”。</p>
     */
    public static String pending(String toolName, String message) {
        return format(toolName, ToolEffectCapture.Signal.PENDING, message);
    }

    /** An input may have been delivered; do not confuse this with user approval. */
    public static String uncertain(String toolName, String message) {
        return format(toolName, ToolEffectCapture.Signal.UNCERTAIN, message);
    }

    /** No input was delivered; the target needs a fresh observation before acting. */
    public static String reobserve(String toolName, String message) {
        return format(toolName, ToolEffectCapture.Signal.REOBSERVE, message);
    }

    /**
     * 构建超时响应
     *
     * @param toolName       工具名称
     * @param timeoutSeconds 超时时间（秒）
     * @param hint           后续操作建议
     * @return 格式化的超时响应
     */
    public static String timeout(String toolName, int timeoutSeconds, String hint) {
        String message = String.format("操作在 %d 秒内未完成", timeoutSeconds);
        if (hint != null && !hint.isEmpty()) {
            message += "，" + hint;
        }
        return format(toolName, ToolEffectCapture.Signal.TIMEOUT, message);
    }

    /**
     * 从异常构建失败响应
     *
     * @param toolName 工具名称
     * @param e        异常
     * @return 格式化的失败响应
     */
    public static String fromException(String toolName, Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        // 如果是超时异常，返回超时格式
        if (e instanceof java.util.concurrent.TimeoutException
                || (e.getCause() != null && e.getCause() instanceof java.util.concurrent.TimeoutException)) {
            return timeout(toolName, 30, "请稍后重试或检查页面状态");
        }
        return error(toolName, msg);
    }

    /**
     * 统一格式化
     */
    private static String format(String toolName, ToolEffectCapture.Signal signal, String message) {
        ToolEffectCapture.note(toolName, signal);
        String displayStatus = switch (signal) {
            case SUCCESS -> "成功";
            case ERROR -> "失败";
            case TIMEOUT -> "超时";
            case PENDING -> "待审";
            case UNCERTAIN -> "结果未知";
            case REOBSERVE -> "待观察";
        };
        return String.format("[%s][%s] %s", toolName, displayStatus, message);
    }
}
