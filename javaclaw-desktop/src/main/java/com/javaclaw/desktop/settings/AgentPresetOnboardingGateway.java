package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.PermissionPresetInstantiationResult;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;

/** 首次智能体初始化只通过 Java SDK 使用的窄异步边界。 */
public interface AgentPresetOnboardingGateway {
    /**
     * 读取固定 Workspace 所需的全部权威目录。
     *
     * @param workspaceId 固定 Workspace
     * @return 预设、Provider、Profile、权限和直接绑定
     */
    CompletionStage<AgentPresetOnboardingCatalog> load(WorkspaceId workspaceId);

    /**
     * 预览但不保存一份权限预设。
     *
     * @param request 精确预设、Workspace 和确定性目标标识
     * @return 服务端权威预览
     */
    CompletionStage<PermissionPresetPreview> previewPermission(PermissionPresetInstantiationRequest request);

    /**
     * 在用户确认后实例化一份普通 PermissionProfile。
     *
     * @param request 与已确认预览一致的请求
     * @param options 确定性幂等键，expected revision 为 0
     * @return 已持久化结果
     */
    CompletionStage<PermissionPresetInstantiationResult> instantiatePermission(
            PermissionPresetInstantiationRequest request, CommandOptions options);

    /**
     * 按持久化权限版本读取当前能力上限内的工具候选。
     *
     * @param workspaceId 固定 Workspace
     * @param permissionProfile 精确权限版本
     * @return 有界、稳定排序的候选
     */
    CompletionStage<List<ToolDescriptor>> searchTools(WorkspaceId workspaceId, PermissionProfileRef permissionProfile);

    /**
     * 将用户选择的精确工具名写入权限新版本。
     *
     * @param profile 完整新版本
     * @param options 确定性幂等键和上一版本号
     * @return 已保存版本
     */
    CompletionStage<PermissionProfile> updatePermission(PermissionProfile profile, CommandOptions options);

    /**
     * 使用内置预设内容创建普通、可编辑 Agent Profile。
     *
     * @param id Workspace 确定性标识
     * @param spec 完整普通 Profile 配置
     * @param options 确定性幂等键，expected revision 为 0
     * @return 已创建 Profile
     */
    CompletionStage<AgentProfile> createProfile(String id, AgentProfileSpec spec, CommandOptions options);

    /**
     * 只将 default Profile 的精确版本绑定为 Workspace 默认。
     *
     * @param workspaceId 固定 Workspace
     * @param profile default Profile 精确版本
     * @param options 绑定自身的确定性幂等键和 expected revision
     * @return 已提交绑定
     */
    CompletionStage<ProfileBinding> bindDefault(
            WorkspaceId workspaceId, AgentProfileRef profile, CommandOptions options);
}
