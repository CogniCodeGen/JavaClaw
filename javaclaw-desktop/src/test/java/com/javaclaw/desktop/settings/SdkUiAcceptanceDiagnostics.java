package com.javaclaw.desktop.settings;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.DesktopClientConnector;
import com.javaclaw.desktop.state.DesktopState;

/** 验收专用失败探针；保留真实连接的首个底层异常，不改变请求、通知和 Desktop 状态。 */
final class SdkUiAcceptanceDiagnostics implements AutoCloseable {
    private final AtomicReference<Throwable> connectionFailure = new AtomicReference<>();
    private final CopyOnWriteArrayList<AutoCloseable> subscriptions = new CopyOnWriteArrayList<>();

    DesktopClientConnector observe(DesktopClientConnector connector) {
        return notifications -> {
            JavaClawClient client = connector.connect(notifications);
            try {
                // SDK 未公开传输诊断入口；测试读取既有连接，仅订阅其 onFailure，不另建 RPC 连接。
                var field = JavaClawClient.class.getDeclaredField("connection");
                field.setAccessible(true);
                RpcClientConnection connection = (RpcClientConnection) field.get(client);
                subscriptions.add(connection.onFailure(failure -> connectionFailure.compareAndSet(null, failure)));
                return client;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                try {
                    client.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw new IOException("无法安装 SDK 验收失败探针", failure);
            }
        };
    }

    void report(Path output, DesktopState state, Throwable failure) throws IOException {
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        writer.println("SDK_UI_ACCEPTANCE_FAILED");
        writer.println("connection=" + state.connection());
        writer.println(
                "workspace=" + state.threads().selectedWorkspace().map(value -> value.id() + "/" + value.name()));
        writer.println("thread=" + state.threads().selectedThread().map(value -> value.id() + "/" + value.title()));
        writer.println("activeTurn=" + state.threads().activeTurn().map(value -> value.id() + "/" + value.status()));
        writer.println("busy=" + state.interaction().busy());
        writer.println("historyCount=" + state.transcript().history().size());
        writer.println("itemCount=" + state.transcript().items().size());
        writer.println("nextSequence=" + state.transcript().nextSequence());
        writer.println("stream="
                + state.transcript().stream()
                        .map(value -> value.turnId() + "/" + value.cursor() + "/terminal=" + value.terminal()));
        writer.println("acceptanceFailure:");
        failure.printStackTrace(writer);
        writer.println("firstConnectionFailure:");
        Throwable transport = connectionFailure.get();
        if (transport == null) {
            writer.println("尚未收到 SDK 连接失败通知");
        } else {
            transport.printStackTrace(writer);
        }
        writer.flush();
        Files.writeString(output.resolve("sdk-ui-failure.txt"), text.toString());
        System.err.print(text);
    }

    @Override
    public void close() throws Exception {
        try {
            for (AutoCloseable subscription : subscriptions) {
                subscription.close();
            }
        } finally {
            subscriptions.clear();
        }
    }
}
