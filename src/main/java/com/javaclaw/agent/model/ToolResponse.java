package com.javaclaw.agent.model;

/**
 * 结构化工具响应 — 统一所有工具方法的返回格式
 *
 * <p>为智能体提供一致的工具执行结果格式，包含明确的状态标识，
 * 使模型能准确判断工具调用是否成功，并据此决定后续动作。</p>
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

    /** 状态标识 */
    private static final String STATUS_SUCCESS = "成功";
    private static final String STATUS_ERROR = "失败";
    private static final String STATUS_TIMEOUT = "超时";

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
        return format(toolName, STATUS_SUCCESS, message);
    }

    /**
     * 构建失败响应
     *
     * @param toolName 工具名称
     * @param message  错误描述
     * @return 格式化的失败响应
     */
    public static String error(String toolName, String message) {
        return format(toolName, STATUS_ERROR, message);
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
        return format(toolName, STATUS_TIMEOUT, message);
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
     * 判断工具响应是否为成功状态
     *
     * @param response 工具响应字符串
     * @return true 表示成功
     */
    public static boolean isSuccess(String response) {
        return response != null && response.contains("[" + STATUS_SUCCESS + "]");
    }

    /**
     * 统一格式化
     */
    private static String format(String toolName, String status, String message) {
        return String.format("[%s][%s] %s", toolName, status, message);
    }
}
