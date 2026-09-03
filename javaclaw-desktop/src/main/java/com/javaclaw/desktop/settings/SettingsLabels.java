package com.javaclaw.desktop.settings;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import javafx.util.StringConverter;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.InstructionScope;
import com.javaclaw.api.ManagedWorktreeArtifactKind;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpHealthState;
import com.javaclaw.api.McpOAuthState;
import com.javaclaw.api.McpSamplingRole;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.PermissionLayerKind;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.PromptSourceKind;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultManagementAction;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.BundleRpcContracts;

/** 设置中心统一使用的用户可见中文标签。 */
final class SettingsLabels {
    private static final Map<String, String> CONTRIBUTION_LABELS = Map.ofEntries(
            Map.entry("TOOL", "工具"),
            Map.entry("CONTEXT", "上下文"),
            Map.entry("ORCHESTRATOR", "任务编排"),
            Map.entry("TIMER", "定时器"),
            Map.entry("SCHEDULABLE_ACTION", "可定时动作"),
            Map.entry("COMMAND", "命令"),
            Map.entry("QUERY", "查询"),
            Map.entry("VIEW", "页面"),
            Map.entry("SKILL", "技能"),
            Map.entry("MCP", "MCP 连接"),
            Map.entry("HOOK", "生命周期钩子"),
            Map.entry("SERVICE", "受控服务"));

    private SettingsLabels() {}

    static <T> StringConverter<T> converter(Function<T, String> labels) {
        Function<T, String> checked = Objects.requireNonNull(labels, "labels");
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : checked.apply(value);
            }

            @Override
            public T fromString(String value) {
                throw new UnsupportedOperationException("设置下拉框不支持自由文本输入");
            }
        };
    }

    static String providerAdapter(ProviderAdapter value) {
        return switch (value) {
            case OPENAI_COMPATIBLE -> "OpenAI 兼容接口";
            case ANTHROPIC -> "Anthropic";
            case GOOGLE_GENAI -> "Google Gemini";
            case OPENAI_RESPONSES -> "OpenAI Responses 接口";
        };
    }

    static String providerLifecycle(ProviderLifecycle value) {
        return switch (value) {
            case ACTIVE -> "启用";
            case DISABLED -> "停用";
            case ARCHIVED -> "已归档";
        };
    }

    static String providerReadiness(ProviderReadiness value) {
        return switch (value) {
            case READY -> "可以使用";
            case CREDENTIAL_UNVERIFIED -> "密钥尚未验证";
            case CREDENTIAL_REQUIRED -> "需要配置密钥";
            case CREDENTIAL_UNAVAILABLE -> "密钥不可用";
            case DISABLED -> "已停用";
            case ARCHIVED -> "已归档";
            case INVALID_CONFIGURATION -> "配置有误";
        };
    }

    static String providerVerificationState(ProviderVerificationState value) {
        return switch (value) {
            case SUCCEEDED -> "验证成功";
            case FAILED -> "验证失败";
            case TIMED_OUT -> "验证超时";
            case CANCELLED -> "已取消";
            case UNKNOWN_OUTCOME -> "结果未知，请勿自动重试";
        };
    }

    static String profileLifecycle(ProfileLifecycle value) {
        return switch (value) {
            case ACTIVE -> "启用";
            case DISABLED -> "停用";
            case ARCHIVED -> "已归档";
        };
    }

    static String privateNetworkPurpose(PrivateNetworkPurpose value) {
        return switch (value) {
            case MCP -> "MCP 外部工具";
            case SITE -> "网站会话";
        };
    }

    static String toolRisk(ToolRisk value) {
        return switch (value) {
            case READ_ONLY -> "只读";
            case WORKSPACE_WRITE -> "可写工作区";
            case NETWORK -> "可访问网络";
            case PROCESS -> "可运行命令";
            case EXTERNAL_EFFECT -> "可执行外部操作";
        };
    }

    static String approvalRequirement(ApprovalRequirement value) {
        return switch (value) {
            case NONE -> "无需逐次确认";
            case RISKY -> "高风险操作需要确认";
            case EVERY_CALL -> "每次调用都要确认";
        };
    }

    static String permissionProfile(String id) {
        return "standard".equals(id) ? "受限对话" : id;
    }

    static String mcpAuthType(McpAuthType value) {
        return switch (value) {
            case NONE -> "无需认证";
            case BEARER -> "Bearer 令牌";
            case API_KEY -> "API 密钥";
            case OAUTH_2_1_PKCE -> "OAuth 2.1（PKCE）";
        };
    }

    static String mcpEndpointState(McpEndpointState value) {
        return switch (value) {
            case ENABLED -> "已启用";
            case DISABLED -> "已停用";
        };
    }

    static String mcpTransport(McpTransport value) {
        return switch (value) {
            case STREAMABLE_HTTPS -> "HTTPS 连接";
            case SIGNED_BUNDLE_STDIO -> "签名扩展包本地连接";
        };
    }

    static String mcpHealthState(McpHealthState value) {
        return switch (value) {
            case UNKNOWN -> "尚未检查";
            case HEALTHY -> "正常";
            case AUTH_REQUIRED -> "需要认证";
            case UNAVAILABLE -> "不可用";
            case PROTOCOL_MISMATCH -> "协议版本不兼容";
        };
    }

    static String mcpCatalogKind(McpCatalogKind value) {
        return switch (value) {
            case TOOL -> "工具";
            case PROMPT -> "提示词";
            case RESOURCE -> "资源";
        };
    }

    static String mcpOAuthState(McpOAuthState value) {
        return switch (value) {
            case PENDING -> "等待授权";
            case AUTHORIZED -> "已授权";
            case FAILED -> "授权失败";
            case EXPIRED -> "已过期";
            case CANCELLED -> "已取消";
        };
    }

    static String mcpSamplingRole(McpSamplingRole value) {
        return switch (value) {
            case USER -> "用户";
            case ASSISTANT -> "助手";
        };
    }

    static String promptSourceKind(PromptSourceKind value) {
        return switch (value) {
            case CORE_TEMPLATE -> "内置模板";
            case AGENT_PROFILE -> "智能体方案";
            case PROJECT_INSTRUCTION -> "项目约定";
            case SKILL -> "技能";
            case CONTEXT -> "扩展上下文";
        };
    }

    static String promptOptimizationState(PromptOptimizationState value) {
        return switch (value) {
            case QUEUED -> "等待运行";
            case RUNNING -> "运行中";
            case READY -> "草稿已生成";
            case FAILED -> "生成失败";
            case CANCELLED -> "已取消";
        };
    }

    static String permissionLayer(PermissionLayerKind value) {
        return switch (value) {
            case SYSTEM_CEILING -> "平台安全上限";
            case WORKSPACE -> "工作区范围";
            case PROFILE -> "智能体权限方案";
            case TURN_GRANT -> "本次任务授权";
            case TOOL_DECLARATION -> "工具自身限制";
        };
    }

    static String securityGrantState(SecurityGrantState value) {
        return switch (value) {
            case ACTIVE -> "有效";
            case REVOKED -> "已撤销";
        };
    }

    static String vaultState(VaultState value) {
        return switch (value) {
            case READY -> "可以使用";
            case LOCKED -> "已锁定";
        };
    }

    static String vaultLockReason(VaultLockReason value) {
        return switch (value) {
            case NONE -> "无";
            case SYSTEM_CREDENTIAL_UNAVAILABLE -> "系统凭据服务不可用";
            case MASTER_KEY_MISSING -> "找不到主密钥";
            case MASTER_KEY_INVALID -> "主密钥无效或无法解锁";
            case CLOSED -> "密钥库已关闭";
        };
    }

    static String vaultManagementAction(VaultManagementAction value) {
        return switch (value) {
            case MASTER_KEY_ROTATED -> "已轮换主密钥";
            case VAULT_RESET -> "已重置密钥库";
        };
    }

    static String extensionState(ExtensionState value) {
        return extensionState(value.name());
    }

    static String extensionState(String value) {
        return switch (value) {
            case "INSTALLED" -> "已安装";
            case "STARTING" -> "正在启动";
            case "ENABLED" -> "已启用";
            case "DISABLED" -> "已停用";
            case "QUARANTINED" -> "已隔离";
            case "REMOVING" -> "正在移除";
            default -> value;
        };
    }

    static String contributionKind(ContributionKind value) {
        return contributionKind(value.name());
    }

    static String contributionKind(String value) {
        return CONTRIBUTION_LABELS.getOrDefault(value, value);
    }

    static String bundleHealth(BundleRpcContracts.HealthState value) {
        return switch (value) {
            case NOT_PROBED -> "尚未检查";
            case HEALTHY -> "正常";
            case DISABLED -> "已停用";
            case BACKING_OFF -> "暂停并等待重试";
            case QUARANTINED -> "已隔离";
        };
    }

    static String trustState(BundleRpcContracts.TrustState value) {
        return switch (value) {
            case ACTIVE -> "有效";
            case REVOKED -> "已撤销";
        };
    }

    static String trashState(BundleRpcContracts.TrashState value) {
        return switch (value) {
            case TRASHED -> "可恢复";
            case RESTORED -> "已恢复";
            case PURGED -> "已永久清除";
        };
    }

    static String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    static String workspaceLifecycle(WorkspaceLifecycle value) {
        return switch (value) {
            case ACTIVE -> "正常";
            case ARCHIVED -> "已归档";
        };
    }

    static String managedWorktreeState(ManagedWorktreeState value) {
        return switch (value) {
            case READY -> "已准备";
            case RUNNING -> "运行中";
            case INTERRUPTED -> "已中断";
            case COMPLETED -> "已完成";
            case CONFLICTED -> "存在冲突";
            case FAILED -> "已失败";
            case APPLYING -> "正在应用补丁";
            case APPLIED -> "补丁已应用";
            case UNKNOWN_OUTCOME -> "结果未知";
            case CLEANING -> "正在清理";
            case CLEANED -> "已清理";
        };
    }

    static String managedWorktreeArtifactKind(ManagedWorktreeArtifactKind value) {
        return switch (value) {
            case PATCH -> "补丁";
            case BACKUP -> "备份";
        };
    }

    static String instructionScope(InstructionScope value) {
        return switch (value) {
            case GLOBAL -> "全局约定";
            case PROJECT -> "项目约定";
        };
    }

    static String executionState(ExecutionState value) {
        return switch (value) {
            case QUEUED -> "等待运行";
            case RUNNING -> "运行中";
            case WAITING_APPROVAL -> "等待审批";
            case WAITING_INPUT -> "等待输入";
            case PAUSED -> "已暂停";
            case COMPLETED -> "已完成";
            case FAILED -> "已失败";
            case CANCELLED -> "已取消";
        };
    }

    static String extensionJobUnitState(ExtensionJobUnitState value) {
        return switch (value) {
            case INTENT_RECORDED -> "已记录执行意图";
            case COMPLETED -> "已完成";
            case FAILED -> "已失败";
        };
    }

    static String automationJobType(String value) {
        return switch (value) {
            case "definition-execution" -> "定义执行";
            case "schedule-occurrence" -> "定时任务执行";
            case "generation-build" -> "知识库生成";
            case "workflow" -> "工作流";
            default -> value;
        };
    }
}
