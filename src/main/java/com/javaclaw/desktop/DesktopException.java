package com.javaclaw.desktop;

/**
 * 桌面自动化操作异常 —— 适配器在底层 CLI 调用失败（非零退出 / 超时 / 权限不足）时抛出。
 *
 * <p>设计为非受检异常：上层工具（{@code DesktopTools}）统一 {@code try/catch} 转为
 * {@link com.javaclaw.agent.model.ToolResponse} 失败响应，领域代码无需层层声明 throws。</p>
 */
public class DesktopException extends RuntimeException {

    public DesktopException(String message) {
        super(message);
    }

    public DesktopException(String message, Throwable cause) {
        super(message, cause);
    }
}
