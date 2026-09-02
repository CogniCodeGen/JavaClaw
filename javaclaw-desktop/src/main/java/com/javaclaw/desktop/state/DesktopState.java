package com.javaclaw.desktop.state;

import java.util.Objects;

/**
 * JavaFX 壳消费的完整不可变状态；各片段按单一变化原因独立演进。
 *
 * @param connection App Server 连接快照
 * @param navigation 导航与面板快照
 * @param threads Workspace、Thread 和活动 Turn 快照
 * @param transcript 当前 Thread 的 Item 投影
 * @param interaction 用户选择、审批与错误快照
 */
public record DesktopState(
        ConnectionState connection,
        NavigationState navigation,
        ThreadState threads,
        TranscriptState transcript,
        InteractionState interaction) {
    /** 校验全部状态片段。 */
    public DesktopState {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(navigation, "navigation");
        Objects.requireNonNull(threads, "threads");
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(interaction, "interaction");
    }

    /** @return Desktop 初始状态 */
    public static DesktopState initial() {
        return new DesktopState(
                ConnectionState.disconnected(),
                NavigationState.initial(),
                ThreadState.empty(),
                TranscriptState.empty(),
                InteractionState.initial());
    }
}
