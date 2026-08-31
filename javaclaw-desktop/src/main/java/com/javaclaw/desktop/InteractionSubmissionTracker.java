package com.javaclaw.desktop;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 按持久 request/approval id 去重内联交互，并在服务端快照确认前保持阻断状态。 */
final class InteractionSubmissionTracker {
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> awaitingPersistence = new HashSet<>();

    synchronized boolean begin(String id) {
        String normalized = Objects.toString(id, "");
        if (normalized.isBlank() || inFlight.contains(normalized) || awaitingPersistence.contains(normalized)) {
            return false;
        }
        inFlight.add(normalized);
        return true;
    }

    synchronized void completed(String id, boolean accepted) {
        inFlight.remove(id);
        if (accepted) {
            awaitingPersistence.add(id);
        }
    }

    synchronized void failed(String id) {
        inFlight.remove(id);
    }

    synchronized boolean blocked(String id) {
        return inFlight.contains(id) || awaitingPersistence.contains(id);
    }

    /** 持久 Item 已离开待处理状态后解除本地阻断；通知本身不能提前解除。 */
    synchronized void reconcile(Set<String> resolvedIds) {
        if (resolvedIds != null) {
            awaitingPersistence.removeAll(resolvedIds);
        }
    }
}
