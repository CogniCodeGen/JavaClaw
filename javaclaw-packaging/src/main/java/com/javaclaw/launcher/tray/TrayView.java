package com.javaclaw.launcher.tray;

import java.util.function.Consumer;

/** SystemTray 与无桌面测试共用的被动视图端口。 */
public interface TrayView extends AutoCloseable {
    /**
     * 绑定唯一命令消费者。
     *
     * @param commands 非阻塞命令入口
     */
    void bind(Consumer<TrayCommand> commands);

    /**
     * 呈现最新不可变状态。
     *
     * @param state 状态
     */
    void render(TrayState state);

    /** 删除托盘图标；不得停止 App Server。 */
    @Override
    void close();
}
