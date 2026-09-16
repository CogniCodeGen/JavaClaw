package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.TurnId;

/** Site 扩展的公开浏览器操作；会话和 Turn 身份由平台上下文绑定，不接受模型自行指定所有者。 */
public final class BrowserCommands {
    /** 组合根中唯一的常驻浏览器服务路由。 */
    public static final String SERVICE = "site.browser.interactive.v1";
    /** 冻结目录中允许产生浏览器观察的精确工具名称。 */
    public static final Set<String> TOOL_NAMES =
            Set.of("browser_open", "browser_act", "browser_screenshot", "browser_tabs", "browser_fill_account");

    private BrowserCommands() {}

    /**
     * @param uri 初始 HTTPS 页面
     * @param account 可选已配置站点账号
     * @param keepLogin 用户是否明确选择保持登录
     */
    public record Open(URI uri, Optional<SiteAccountContracts.Selection> account, boolean keepLogin) {
        /** 校验账号字段；HTTPS 与精确授权在宿主继续复核。 */
        public Open {
            Objects.requireNonNull(uri, "uri");
            account = Objects.requireNonNull(account, "account");
        }
    }

    /**
     * @param available 当前平台是否通过可见隔离浏览器门禁
     * @param session 当前对话的会话
     * @param detail 可展示状态说明
     * @param continuation 已持久化续接状态；即使没有浏览器会话也必须可以显示
     */
    public record Status(
            boolean available,
            Optional<BrowserContracts.SessionView> session,
            String detail,
            Optional<ContinuationStatus> continuation) {
        /** 不读取页面正文的浏览器状态。 */
        public Status {
            session = Objects.requireNonNull(session, "session");
            Objects.requireNonNull(detail, "detail");
            continuation = Objects.requireNonNull(continuation, "continuation");
        }

        /**
         * 构造没有续接记录的兼容状态。
         *
         * @param available 平台能力
         * @param session 当前会话
         * @param detail 脱敏说明
         */
        public Status(boolean available, Optional<BrowserContracts.SessionView> session, String detail) {
            this(available, session, detail, Optional.empty());
        }
    }

    /** 持久续接进度，不承诺已创建或成功执行下一 Turn。 */
    public enum ContinuationState {
        REQUESTED,
        CREATED,
        STARTED,
        FAILED,
        STOPPED
    }

    /** 固定脱敏原因；不携带异常正文、密码或页面内容。 */
    public enum ContinuationFailure {
        NONE,
        BUDGET_EXHAUSTED,
        CHILD_BUDGET_UNAVAILABLE,
        CONFIGURATION_UNAVAILABLE,
        PARENT_NOT_COMPLETED
    }

    /**
     * @param state 持久续接进度
     * @param reason 固定失败原因；正常进度为 NONE
     * @param detail 可直接展示的脱敏说明
     * @param parent 来源 Turn
     * @param child 已提交后继 Turn；未创建时为空
     */
    public record ContinuationStatus(
            ContinuationState state, ContinuationFailure reason, String detail, TurnId parent, Optional<TurnId> child) {
        /** 校验完整状态，不通过说明文字推断失败类别。 */
        public ContinuationStatus {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(parent, "parent");
            child = Objects.requireNonNull(child, "child");
        }
    }

    /** @param forms 用户明确请求的脱敏登录表单，不包含当前输入值 */
    public record LoginForms(java.util.List<BrowserContracts.LoginForm> forms) {
        /** 冻结由 Worker 当前观察创建的表单引用。 */
        public LoginForms {
            forms = java.util.List.copyOf(forms);
        }
    }

    /**
     * @param action 有界动作
     * @param upload 可选当前对话已拥有的上传附件
     */
    public record Act(BrowserContracts.Action action, Optional<AttachmentRef> upload) {
        /** 不允许同时把模型文本当作宿主路径或秘密。 */
        public Act {
            Objects.requireNonNull(action, "action");
            upload = Objects.requireNonNull(upload, "upload");
        }
    }

    /**
     * @param operation 扩展操作名
     * @param payload 已由 SDK 或工具 Schema 验证的参数
     */
    public record Invocation(String operation, com.javaclaw.api.CanonicalPayload payload) {
        /** 校验固定路由载荷。 */
        public Invocation {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(payload, "payload");
        }
    }
}
