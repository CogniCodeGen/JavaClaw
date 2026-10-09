package com.javaclaw.desktop.ffm.macos;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.IntConsumer;

/** Once a system post is entered, failures are uncertain; cleanup releases only held inputs. */
final class MacEventSequence {
    enum Outcome { ACCEPTED, NOT_SENT, FAILED_BEFORE_POST, UNKNOWN }
    private MacEventSequence() { }

    static <T> Outcome deliver(List<T> events, boolean downUpPairs, BooleanSupplier ready,
            Consumer<T> post, Runnable betweenEvents) {
        return deliver(events, downUpPairs, ready, ignored -> true, post, betweenEvents);
    }

    static <T> Outcome deliver(List<T> events, boolean downUpPairs, BooleanSupplier ready,
            IntPredicate beforePost, Consumer<T> post, Runnable betweenEvents) {
        return deliver(events, downUpPairs, ready, beforePost, post, ignored -> betweenEvents.run());
    }

    static <T> Outcome deliver(List<T> events, boolean downUpPairs, BooleanSupplier ready,
            IntPredicate beforePost, Consumer<T> post, IntConsumer afterPost) {
        boolean attempted = false;
        int pendingRelease = -1;
        try {
            for (int index = 0; index < events.size(); index++) {
                if (!ready.getAsBoolean() || !beforePost.test(index))
                    return attempted ? Outcome.UNKNOWN : Outcome.NOT_SENT;
                if (downUpPairs && index % 2 == 0) pendingRelease = index + 1;
                attempted = true;
                post.accept(events.get(index));
                if (downUpPairs && index % 2 == 1) pendingRelease = -1;
                afterPost.accept(index);
            }
            return ready.getAsBoolean() ? Outcome.ACCEPTED : Outcome.UNKNOWN;
        } catch (RuntimeException | LinkageError failure) {
            return attempted ? Outcome.UNKNOWN : Outcome.FAILED_BEFORE_POST;
        } finally {
            if (pendingRelease >= 0 && pendingRelease < events.size()) {
                try { post.accept(events.get(pendingRelease)); }
                catch (RuntimeException | LinkageError ignored) { /* UNKNOWN remains authoritative. */ }
            }
        }
    }
}
