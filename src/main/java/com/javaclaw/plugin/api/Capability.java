package com.javaclaw.plugin.api;

/**
 * 插件能力枚举 —— 插件在 {@code plugin.json} 的 {@code capabilities} 字段声明所需能力，
 * 声明即"权限申请"：宿主据此授权，运行期插件只能拿到已授权能力的句柄。
 *
 * <p>P1 仅实现 {@link #CHAT}；其余能力已在枚举中占位声明，调用时由
 * {@code CapabilityGuard} / 各能力实现抛出未授权或未实现异常，留待 P2 接通。</p>
 *
 * @author JavaClaw
 */
public enum Capability {

    /** AI 流式对话：经宿主隔离编排器（ScheduledTaskAgent）发起一轮对话并取回结果 */
    CHAT("AI 对话"),

    /** 创建定时任务：向宿主 ScheduleManager 注册带插件标签的定时任务（P2） */
    SCHEDULE("定时任务"),

    /** 读取记忆：只读访问编排器/子智能体记忆快照（P2） */
    MEMORY("读取记忆"),

    /** 托管数据存储：在宿主分配的插件数据目录读写键值（P2） */
    STORAGE("数据存储");

    /** 中文显示名（用于 UI 与日志） */
    private final String displayName;

    Capability(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
