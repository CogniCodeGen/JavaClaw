package com.javaclaw.server.extension;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.protocol.ExecutionRpcContracts;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.server.transport.UnixDomainSocketRpcServer;

/** 为真实 Desktop 验收提供隔离的 H2、UDS 和固定模型；数据仅写入调用者指定的新目录。 */
public final class MemoryUiAcceptanceServer {
    private MemoryUiAcceptanceServer() {}

    /**
     * 创建两工作区、文档和待裁决冲突，然后接受真实 SDK 连接。
     *
     * @param arguments 唯一参数为不存在的验收根目录
     * @throws Exception 启动、种子数据或服务失败
     */
    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]).toAbsolutePath();
        Files.createDirectory(root);
        var model = new MemoryIntegrationModel();
        try (var fixture = new MemoryIntegrationFixture(root.resolve("data-v6"), Clock.systemUTC(), model)) {
            fixture.installProvider();
            var workspace = fixture.workspace("界面功能验收");
            fixture.workspace("隔离工作区");
            documents(root.resolve("界面功能验收"));
            grantPreviewRead(fixture, workspace);
            var reference = fixture.conversation(workspace, "记忆事实：办公地点上海");
            MemoryUiReviewFacts.history(fixture, workspace);
            fixture.command(
                    workspace,
                    BuiltinExtensionIds.MEMORY,
                    "create",
                    new MemoryContracts.CreateRequest(
                            "office-location",
                            MemoryContracts.MemoryKind.FACT,
                            "workspace",
                            "记忆事实：办公地点北京",
                            Set.of("conversation"),
                            false,
                            Optional.empty()),
                    0,
                    MemoryContracts.Memory.class);
            MemoryUiReviewFacts.learning(fixture, workspace, reference);
            fixture.awaitBinding(workspace);
            var receipt = fixture.run(workspace, 1);
            fixture.awaitJob(receipt.id(), ExecutionState.COMPLETED);
            if (fixture.conflicts(workspace).rows().size() != 1) {
                throw new AssertionError("验收数据应包含一个真实学习冲突");
            }
            System.out.println("UI_LEARNING_JOB " + receipt.id());
            model.streamConversation = true;
            Path socket = root.resolve("rpc/server.sock");
            try (var server = UnixDomainSocketRpcServer.bind(socket)) {
                Files.writeString(
                        root.resolve("ready.json"),
                        fixture.components
                                .json()
                                .encode(Map.of(
                                        "socket",
                                        socket.toString(),
                                        "workspaceId",
                                        workspace.id(),
                                        "model",
                                        "固定模型，无外部请求",
                                        "learningJobId",
                                        receipt.id()))
                                .json());
                System.out.println("UI_ACCEPTANCE_READY " + socket);
                server.serve(
                        fixture.components.json(),
                        connection -> fixture.components.newSession().serve(connection));
            }
        }
    }

    private static void grantPreviewRead(MemoryIntegrationFixture fixture, Workspace workspace) {
        // 只给本次新建的验收 Workspace 授权；隔离工作区与公共测试夹具仍继承 standard。
        PermissionProfile cloned = fixture.decode(
                fixture.write(
                        "permissionProfile/clone",
                        new PermissionProfileRpcContracts.ClonePayload(
                                new PermissionProfileRef("standard", 1), "ui-preview-read"),
                        0),
                PermissionProfile.class);
        PermissionProfile readable = new PermissionProfile(
                cloned.id(),
                2,
                new FilePermission(List.of(workspace.root()), List.of(), false, false),
                cloned.network(),
                cloned.processes(),
                cloned.tools(),
                cloned.resources());
        fixture.write(
                "permissionProfile/update",
                new PermissionProfileRpcContracts.UpdatePayload(readable),
                cloned.version());
        ExecutionOverrides overrides = new ExecutionOverrides(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new PermissionProfileRef(readable.id(), readable.version())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        fixture.write(
                "execution/default/update",
                new ExecutionRpcContracts.DefaultUpdatePayload(Optional.of(workspace.id()), overrides),
                0);
    }

    private static void documents(Path root) throws Exception {
        Files.writeString(root.resolve("guide.md"), """
            # 文档展示验收

            中文段落、**加粗**、`行内代码`与相对资源应在同一工作区显示。

            | 类型 | 预期 |
            | --- | --- |
            | Markdown | 标题、表格和代码完整 |
            | 图片 | 保留比例与可读尺寸 |

            ```java
            public record WorkspaceMemory(String content, long revision) {}
            ```

            ![验收图](diagram.png)

            [代码文件](Example.java)
            """);
        StringBuilder source = new StringBuilder();
        for (int line = 1; line <= 620; line++) {
            source.append("// 文档分页验收，第 ").append(line).append(" 行\n");
        }
        Files.writeString(root.resolve("Example.java"), source);
        BufferedImage image = new BufferedImage(640, 240, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0xFBFAF6));
            graphics.fillRect(0, 0, 640, 240);
            graphics.setColor(new Color(0x2E9A6A));
            graphics.fillRoundRect(40, 40, 560, 160, 20, 20);
            graphics.setColor(Color.WHITE);
            graphics.drawString("JavaClaw document preview / 640 x 240", 150, 125);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", root.resolve("diagram.png").toFile());
    }
}
