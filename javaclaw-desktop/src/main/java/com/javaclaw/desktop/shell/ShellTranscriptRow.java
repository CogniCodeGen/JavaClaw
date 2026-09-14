package com.javaclaw.desktop.shell;

import java.util.Optional;

import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.desktop.view.PresentedItem;

/**
 * 原生展示行保留源身份，展开与发送操作不依赖可回收的 Cell。
 *
 * @param id 会话内稳定身份，不可空
 * @param presented 可读摘要和详情，不可空
 * @param source 持久历史来源，可缺省
 * @param full 完整正文的现有预览引用，可缺省
 * @param outgoing 本地发送状态，可缺省
 * @param sequence 持久消息序号或本地发送的前序锚点，无单位，纯暂态缺省为 Long.MAX_VALUE
 */
record ShellTranscriptRow(
        String id,
        PresentedItem presented,
        Optional<ItemHistoryEntry> source,
        Optional<DocumentReference> full,
        Optional<OutgoingMessage> outgoing,
        long sequence) {
    String title() {
        return presented.title();
    }

    String body() {
        return presented.body();
    }

    ShellTranscriptRow atSequence(long value) {
        return new ShellTranscriptRow(id, presented, source, full, outgoing, value);
    }
}
