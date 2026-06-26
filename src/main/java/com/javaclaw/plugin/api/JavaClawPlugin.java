package com.javaclaw.plugin.api;

/**
 * 插件入口契约 —— 每个插件 jar 提供且仅提供一个实现类（由 {@code plugin.json} 的 {@code mainClass} 指定）。
 *
 * <p>生命周期由宿主全权驱动：</p>
 * <ul>
 *   <li>{@link #start(PluginContext)} —— 启用时调用一次。插件在此通过 {@code ctx} 申请能力句柄、
 *       注册后台监听/定时任务等。<b>方法应尽快返回</b>，长活逻辑请提交到
 *       {@code ctx.exec().background(...)}（宿主托管虚拟线程），不要在本方法内阻塞死循环。</li>
 *   <li>{@link #stop()} —— 停用/卸载时调用一次。插件应尽量自清理；即便清理不彻底，
 *       宿主仍会兜底回收该插件申请过的全部线程与资源。</li>
 * </ul>
 *
 * <p>实现类必须有公开无参构造器（宿主反射实例化）。</p>
 *
 * @author JavaClaw
 */
public interface JavaClawPlugin {

    /**
     * 启用插件。
     *
     * @param ctx 能力网关：插件获取一切宿主资源（执行器、AI 对话、配置等）的唯一入口
     * @throws Exception 启动失败将被宿主捕获，插件置为 FAILED 并回收
     */
    void start(PluginContext ctx) throws Exception;

    /**
     * 停用插件。应尽量释放自身状态（宿主会另行兜底回收托管资源）。
     */
    void stop();
}
