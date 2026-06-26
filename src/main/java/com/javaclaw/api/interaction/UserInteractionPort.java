package com.javaclaw.api.interaction;

/**
 * 用户交互端口（UI 无关）。
 *
 * <p>领域层需要向用户弹窗确认、二次确认、发通知时，不直接依赖 JavaFX 或其他 UI 框架，
 * 而是调用本端口。由 UI 层在启动时提供具体实现：
 * <ul>
 *   <li>JavaFX：弹 {@code Alert}/{@code TextInputDialog}</li>
 *   <li>Web：通过 WebSocket 推送请求、等待前端回传</li>
 *   <li>CLI：stdin 读一行</li>
 * </ul>
 *
 * <p>本接口的所有实现必须支持从任意线程调用（典型调用来自 Reactor 线程池），
 * 并在内部完成必要的线程切换。</p>
 */
public interface UserInteractionPort {

    /**
     * 发起一次同步确认（阻塞直到用户响应或超时）。
     *
     * <p>为避免每个调用者都自己处理 {@link ConfirmKind#NOTIFY} 的直接放行逻辑，
     * 本接口的 NOTIFY 语义如下：实现端可选择弹出非阻塞 Toast，**始终返回 true**。</p>
     *
     * @param request 确认请求
     * @return true=放行，false=拒绝（含超时、异常、用户取消等）
     */
    boolean confirm(ConfirmRequest request);

    /**
     * 发起一次三态确认（阻塞直到用户响应或超时）。
     *
     * <p>UI 实现需要展示三个选项：拒绝 / 同意一次 / 同意全部。
     * "同意全部"由调用方解读为"本次会话内该工具默认放行"，UI 端无需关心后续逻辑。</p>
     *
     * <p>默认实现回退到 {@link #confirm(ConfirmRequest)} 的二态语义；UI 适配层应覆盖
     * 此方法并提供完整的三态弹窗。</p>
     */
    default ConfirmDecision confirmEx(ConfirmRequest request) {
        return confirm(request) ? ConfirmDecision.ALLOW_ONCE : ConfirmDecision.DENY;
    }

    /**
     * 发送一条纯通知（不等待响应）。
     *
     * <p>实现端应立即返回；典型场景是 {@link ConfirmKind#NOTIFY} 级别的工具放行前
     * 让用户知情。若实现不可用（例如 UI 未就绪），应静默失败或降级为日志输出。</p>
     *
     * @param request 通知请求
     */
    void notify(ToastRequest request);

    /**
     * 端口是否可用（UI 已就绪）。
     *
     * <p>默认认为总是可用。实现可覆盖以避免在 UI 未就绪时阻塞调用者。</p>
     */
    default boolean isAvailable() {
        return true;
    }
}
