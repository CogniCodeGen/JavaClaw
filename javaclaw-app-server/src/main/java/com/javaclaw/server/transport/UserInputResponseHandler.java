package com.javaclaw.server.transport;

/** 将用户输入或取消结果交给运行时的窄接口。 */
@FunctionalInterface
public interface UserInputResponseHandler {
    /** 提交 requestId 对应的文本或取消结果；返回是否接受回复，重复或失效请求不能再次恢复 Turn。 */
    boolean respond(String requestId, String value, boolean cancelled);
}
