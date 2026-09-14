package com.javaclaw.desktop.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 将本地提交保留在首发时的持久序号之后；重试及后续权威消息不能改变原文在会话中的相对位置。 */
final class ChatProjectionOrder {
    private ChatProjectionOrder() {}

    static <T> List<T> merge(List<T> committed, List<Long> sequences, List<T> previous, List<Pending<T>> outgoings) {
        List<T> result = new ArrayList<>();
        int index = 0;
        boolean previousAdded = false;
        for (Pending<T> outgoing : outgoings.stream()
                .sorted(Comparator.comparingLong(Pending::afterSequence))
                .toList()) {
            while (index < committed.size() && sequences.get(index) <= outgoing.afterSequence()) {
                result.add(committed.get(index++));
            }
            if (!previousAdded) {
                result.addAll(previous);
                previousAdded = true;
            }
            result.addAll(outgoing.rows());
        }
        result.addAll(committed.subList(index, committed.size()));
        if (!previousAdded) {
            result.addAll(previous);
        }
        return result;
    }

    /** afterSequence 是首发时已经接收的最大持久序号；rows 为本地用户和同 Turn 助手的非空有序行组。 */
    record Pending<T>(long afterSequence, List<T> rows) {}
}
