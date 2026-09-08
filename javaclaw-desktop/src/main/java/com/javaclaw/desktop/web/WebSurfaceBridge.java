package com.javaclaw.desktop.web;

import java.util.Objects;
import java.util.function.Consumer;

/** 仅向受信任应用模板暴露单个消息入口；宿主持有强引用，外部内容不能取得 SDK 或文件对象。 */
public final class WebSurfaceBridge {
    private final Consumer<String> receiver;

    WebSurfaceBridge(Consumer<String> receiver) {
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    /**
     * 接收带宿主代次、上下文和绘制版本的有界消息。
     *
     * @param message 应用模板序列化的 JSON；超限或旧代次由宿主拒绝
     */
    public void post(String message) {
        if (message != null && message.length() <= 16_384) {
            receiver.accept(message);
        }
    }
}
