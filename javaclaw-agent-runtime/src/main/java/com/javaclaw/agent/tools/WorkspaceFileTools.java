package com.javaclaw.agent.tools;

import java.util.List;

import com.javaclaw.agent.knowledge.SkillResource;
import com.javaclaw.agent.knowledge.SkillScriptGateway;
import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.ToolHandler;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolRisk;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 第一方文件/Java 工具描述；模型只提出内容与相对路径，固定 Worker 负责实际受限执行。 */
public final class WorkspaceFileTools {
    private WorkspaceFileTools() {}

    /** 创建有 Schema 的读、列举、原子写与唯一片段补丁工具；写操作必须携带刚读取的 SHA-256。 */
    public static List<RegisteredTool> create(FileOperationGateway worker, SandboxPolicy ceiling) {
        return List.of(
                file(
                        worker,
                        ceiling,
                        "file_read",
                        "READ",
                        "按行读取工作区文本，返回全文件 SHA-256。",
                        "\"startLine\":{\"type\":\"integer\",\"minimum\":1},\"lineCount\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500}",
                        ""),
                file(worker, ceiling, "file_list", "LIST", "列举工作区目录；不跟随符号链接，最多一千项。", "", ""),
                file(
                        worker,
                        ceiling,
                        "file_write",
                        "WRITE",
                        "写入最多一 MiB UTF-8；expectedSha256 必须为读取摘要，新建使用 MISSING。",
                        "\"content\":{\"type\":\"string\",\"maxLength\":1000000},\"expectedSha256\":{\"type\":\"string\",\"pattern\":\"^([a-f0-9]{64}|MISSING)$\"}",
                        ",\"content\",\"expectedSha256\""),
                file(
                        worker,
                        ceiling,
                        "file_patch",
                        "REPLACE",
                        "以精确唯一片段应用补丁；必须提供原文件 SHA-256，不匹配则拒绝。",
                        "\"content\":{\"type\":\"string\",\"maxLength\":1000000},\"oldText\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1000000},\"expectedSha256\":{\"type\":\"string\",\"pattern\":\"^[a-f0-9]{64}$\"}",
                        ",\"content\",\"oldText\",\"expectedSha256\""));
    }

    /** JShell 必须通过子 JVM Worker；代码不在 App Server 中编译或执行。 */
    public static RegisteredTool javaCode(SkillScriptGateway worker, SandboxPolicy ceiling) {
        String schema =
                "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"code\"],\"properties\":{\"code\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1000000}}}";
        return new RegisteredTool(
                new ToolDescriptor("java_execute", "在无原始网络的沙箱子 JVM 执行 Java/JShell 片段。", schema),
                ToolOrigin.BUILTIN,
                ToolRisk.HIGH,
                false,
                ceiling,
                context -> {
                    var result = worker.execute(
                            new SkillResource(
                                    "snippet.jsh",
                                    "text/x-java",
                                    context.arguments().path("code").asText(),
                                    true),
                            context.call(),
                            context.sandboxPolicy());
                    return new ToolHandler.Result(result.item(), result.modelContent());
                });
    }

    private static RegisteredTool file(
            FileOperationGateway worker,
            SandboxPolicy ceiling,
            String name,
            String operation,
            String description,
            String properties,
            String required) {
        boolean readOnly = operation.equals("READ") || operation.equals("LIST");
        String schema = "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"path\"" + required
                + "],\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1000}"
                + (properties.isEmpty() ? "" : "," + properties) + "}}";
        var tool = new RegisteredTool(
                new ToolDescriptor(name, description, schema),
                ToolOrigin.BUILTIN,
                readOnly ? ToolRisk.LOW : ToolRisk.HIGH,
                false,
                ceiling,
                context -> {
                    var input = context.arguments();
                    var request = new FileOperationGateway.FileRequest(
                            operation,
                            input.path("path").asText(),
                            input.path("content").asText(""),
                            input.path("expectedSha256").asText(""),
                            input.path("oldText").asText(""),
                            input.path("startLine").asInt(1),
                            input.path("lineCount").asInt(200));
                    var result = worker.execute(request, context.call(), context.sandboxPolicy());
                    return new ToolHandler.Result(result.item(), result.modelContent());
                });
        return readOnly ? tool.readOnly() : tool;
    }
}
