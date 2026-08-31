package com.javaclaw.agent.tool;

import java.util.List;

import com.javaclaw.agent.runtime.ToolExecutionContext;

/** 与传输无关的用户输入等待端口；请求注册必须先于通知发送，避免极速回复丢失。 */
@FunctionalInterface
public interface UserInputGateway {
    /** Implementations register the request before invoking {@code announcePending}. */
    Response ask(Request request, Runnable announcePending) throws Exception;

    /**
     * 提交给客户端的问题及归属调用，不携带模型执行控制权。
     *
     * @param requestId 问题关联标识，发布前必须注册
     * @param call 当前工具调用及 Thread/Turn 配置快照，调用时非空
     * @param prompt 问题文本，发布 Item 时要求非空白
     * @param choices 可选回答列表；null 归一为空列表，空列表表示自由输入
     */
    record Request(String requestId, ToolExecutionContext call, String prompt, List<String> choices) {
        /** 复制选项列表以隔离 UI/调用方修改；发布时继续校验交互内容。 */
        public Request {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    /**
     * 一次回答或取消的结果；取消不得当作普通空答案继续执行。
     *
     * @param value 回答文本；null 归一为空字符串
     * @param cancelled 是否取消本次问题
     */
    record Response(String value, boolean cancelled) {
        /** 归一缺失回答并保留独立取消标记，避免两种语义混淆。 */
        public Response {
            value = value == null ? "" : value;
        }
    }
}
