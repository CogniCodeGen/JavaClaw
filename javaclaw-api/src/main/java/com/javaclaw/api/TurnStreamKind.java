package com.javaclaw.api;

/** 公开聊天流的固定版本事件类别，不包含内部推理或工具参数。 */
public enum TurnStreamKind {
    /** 持久模型意图已建立。 */
    STARTED,
    /** 已持久化的助手正文增量。 */
    TEXT_DELTA,
    /** 最终助手 Item 与 checkpoint 已原子提交。 */
    COMMITTED,
    /** 本次调用关闭，没有可替换的最终正文。 */
    CLOSED,
    /** Turn 已到终态。 */
    TURN_FINISHED
}
