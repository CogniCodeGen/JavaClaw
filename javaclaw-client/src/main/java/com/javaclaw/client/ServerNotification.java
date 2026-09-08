package com.javaclaw.client;

import java.util.Objects;

import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcNotification;

/** SDK 对服务端通知的强类型投影；未知通知保留原始 envelope，供协商后的未来能力处理。 */
public sealed interface ServerNotification
        permits ServerNotification.ExtensionChanged,
                ServerNotification.TurnStream,
                ServerNotification.DocumentInvalidated,
                ServerNotification.Unknown {
    /**
     * 当前连接的文档快照已经失效；界面应清除对应的文档内容。
     *
     * @param event 句柄和固定失效原因
     */
    record DocumentInvalidated(com.javaclaw.protocol.DocumentPreviewRpcContracts.Invalidated event)
            implements ServerNotification {
        /** 校验事件。 */
        public DocumentInvalidated {
            Objects.requireNonNull(event, "event");
        }
    }
    /**
     * Extension 资源失效通知。
     *
     * <p>通知不携带业务正文；收到后应按标识和 revision 重新读取权威状态。
     *
     * @param event Extension 资源标识与 revision
     */
    record ExtensionChanged(ExtensionRpcContracts.ExtensionEvent event) implements ServerNotification {
        /** 校验事件。 */
        public ExtensionChanged {
            Objects.requireNonNull(event, "event");
        }
    }

    /**
     * 已协商的公开聊天流批量或有序水位。
     *
     * @param event 只包含正文的强类型通知
     */
    record TurnStream(com.javaclaw.protocol.TurnStreamRpcContracts.Notification event) implements ServerNotification {
        /** 校验通知。 */
        public TurnStream {
            Objects.requireNonNull(event, "event");
        }
    }

    /**
     * 当前 SDK 尚未识别的协商后通知。
     *
     * @param notification 原始 JSON-RPC notification
     */
    record Unknown(JsonRpcNotification notification) implements ServerNotification {
        /** 校验原始通知。 */
        public Unknown {
            Objects.requireNonNull(notification, "notification");
        }
    }
}
