package com.javaclaw.server.turn;

import java.util.List;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserResult;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelImage;

/** 只接受冻结 Site 工具的版本化图片结果；任意其他工具的同名 JSON 字段不产生图片能力。 */
public final class BrowserImageProjection {
    private BrowserImageProjection() {}

    /**
     * 从真实工具身份及宿主附件结果提取图片引用，实时执行和历史回放共用。
     *
     * @param json 规范 codec
     * @param workspace 所有者 Workspace
     * @param thread 所有者 Thread
     * @param tool 持久或冻结的完整工具身份
     * @param payload 成功工具输出
     * @return 没有截图时为空列表
     */
    public static List<ModelImage> project(
            CanonicalJson json, WorkspaceId workspace, ThreadId thread, ToolIdentity tool, CanonicalPayload payload) {
        if (!BuiltinExtensionIds.SITE.equals(tool.producerId())
                || !BrowserCommands.TOOL_NAMES.contains(tool.name())
                || json.objectField(payload, "observation").isEmpty()) {
            return List.of();
        }
        BrowserResult result = json.decode(payload, BrowserResult.class);
        var observation = result.observation();
        var owner = observation.session().owner();
        if (!owner.workspaceId().equals(workspace) || !owner.threadId().equals(thread)) {
            throw new SecurityException("浏览器图片不属于当前对话");
        }
        if (observation.frame().isEmpty()) {
            return List.of();
        }
        var frame = observation.frame().orElseThrow();
        return List.of(new ModelImage(
                result.attachment().orElseThrow(),
                workspace,
                thread,
                frame.frameId(),
                frame.imageWidth(),
                frame.imageHeight()));
    }
}
