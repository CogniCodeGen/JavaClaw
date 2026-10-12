package com.javaclaw.plugin.sdk;

import com.javaclaw.plugin.protocol.v2.Contribution;
import com.javaclaw.plugin.protocol.v2.HookRequest;
import com.javaclaw.plugin.protocol.v2.HookResponse;
import java.util.List;

/** 进程外插件入口，插件只依赖 SDK 和协议，不依赖 Desktop。 */
public interface Plugin extends AutoCloseable {
    List<Contribution> contributions();
    default List<String> validateConfiguration(String configurationJson) { return List.of(); }
    default void start(PluginContext context) throws Exception { }
    default void execute(PluginCall call, PluginEvents events) throws Exception {
        throw new UnsupportedOperationException("插件没有实现该流式能力");
    }
    default String retrieve(PluginCall call) throws Exception {
        throw new UnsupportedOperationException("插件没有实现上下文能力");
    }
    default HookResponse hook(HookRequest request, Cancellation cancellation) throws Exception {
        throw new UnsupportedOperationException("插件没有实现该数据钩子");
    }
    /** 停止接受新的后台入口，保留已开始的请求；宿主仍可调用已有运行租约。 */
    default void beginDrain() throws Exception { }
    @Override default void close() throws Exception { }
}
