package com.javaclaw.client.cli;

import java.nio.file.Files;
import java.nio.file.Path;

import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.StreamRpcConnection;

/** 真实子进程中的最小测试协议对端；仅模拟持久 Turn 响应，不调用模型。 */
public final class CliStdioTestServer {
    private CliStdioTestServer() {}

    /**
     * 服务当前 stdio 会话，并记录连接是在终态之前还是之后关闭。
     *
     * @param arguments 生命周期结果文件；第二个参数可为 await-cancel，使 Turn 持续运行至收到取消
     * @throws Exception 测试协议或文件写入失败
     */
    public static void main(String[] arguments) throws Exception {
        Path marker = Path.of(arguments[0]);
        boolean awaitCancel = arguments.length > 1 && arguments[1].equals("await-cancel");
        CliTestPeer peer = new CliTestPeer();
        peer.onRead = () -> {
            if (!awaitCancel) {
                peer.current = CliTestPeer.turn(peer.reads < 3 ? TurnStatus.RUNNING : TurnStatus.COMPLETED, 3);
            }
            return peer.current;
        };
        Files.writeString(marker, "started");
        try (var connection = new StreamRpcConnection(System.in, System.out, new JsonRpcCodec())) {
            while (true) {
                var message = connection.receive();
                connection.send(peer.respond((JsonRpcRequest) message));
            }
        } catch (java.io.EOFException end) {
            String result = awaitCancel
                    ? peer.current.status().name().toLowerCase(java.util.Locale.ROOT) + ":closed"
                    : peer.current.status() == TurnStatus.COMPLETED ? "completed:closed" : "early:closed";
            Files.writeString(marker, result);
        }
    }
}
