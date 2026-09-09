package com.javaclaw.desktop.acceptance.chatdocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javafx.application.Platform;

import com.javaclaw.protocol.CanonicalJson;

/** 使用与 JUnit 相同的维护型场景重放真实窗口并收集原始截图；不需要服务端、用户文件或模型。 */
public final class ChatDocumentAcceptanceReplay {
    private ChatDocumentAcceptanceReplay() {}

    /**
     * 重放聊天和文档视觉验收；任何断言失败以非零退出码暴露。
     *
     * @param arguments 可选的原始截图输出目录
     * @throws Exception 窗口、内容解析或产物写入失败
     */
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments.length == 0 ? "target/acceptance/chat-document" : arguments[0])
                .toAbsolutePath();
        System.setProperty("javaclaw.acceptance.output", output.toString());
        try {
            ChatDocumentAcceptanceTest chat = new ChatDocumentAcceptanceTest();
            chat.普通历史对话不显示文档按钮而真实文档保留可点击超链接并保存截图();
            chat.同一内容复用原生单元格与WebView并保存样式对照();
            chat.欢迎区居中及角色字号字重沿用原生语义并保存空态对照();
            chat.流式完成替换同一消息且真实代码文件入口长正文和切换均可操作();
            chat.历史阅读时追加正文及重建页面保留同一可见消息和相对位置();
            DocumentVisualAcceptanceTest documents = new DocumentVisualAcceptanceTest();
            documents.Markdown表格代码相对图片和文档链接通过只读网关显示();
            documents.代码目标行分页前后切换及健康页面简版重试均保留正文();
            documents.图片格式校验与撤权清除及不支持格式元信息均在真实面板可见();
            documents.窄文档侧栏完整展示所有按钮名称且自动换行();
            try (var files = Files.list(output)) {
                var index = files.filter(file -> file.getFileName().toString().endsWith(".png"))
                        .map(file -> file.getFileName().toString())
                        .sorted()
                        .toList();
                Files.writeString(
                        output.resolve("index.json"),
                        new CanonicalJson()
                                .encode(Map.of(
                                        "scenes",
                                        index,
                                        "functionalScenariosPassed",
                                        9,
                                        "capture",
                                        "真实JavaFX Scene原始像素；不是OS光学观测",
                                        "limitations",
                                        "仅当前平台；未证明其他Runner或macOS WebKit彩色emoji显示通过"))
                                .json());
            }
            System.out.println("聊天与文档验收通过，原始截图：" + output);
        } finally {
            Platform.exit();
        }
    }
}
