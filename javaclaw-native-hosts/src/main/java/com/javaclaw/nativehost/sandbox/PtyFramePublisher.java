package com.javaclaw.nativehost.sandbox;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Flow;

import com.javaclaw.api.SandboxFrame;

/** 单订阅者有界 PTY Publisher；队列满时读取线程停在 OS PTY 边界，直到下游增加 demand。 */
final class PtyFramePublisher implements Flow.Publisher<SandboxFrame> {
    private static final int MAX_BUFFERED_FRAMES = 32;

    private final ArrayDeque<SandboxFrame> queue = new ArrayDeque<>();
    private Flow.Subscriber<? super SandboxFrame> subscriber;
    private long demand;
    private boolean producerDone;
    private boolean cancelled;
    private boolean draining;
    private boolean terminalDelivered;
    private Throwable failure;

    @Override
    public void subscribe(Flow.Subscriber<? super SandboxFrame> requestedSubscriber) {
        Flow.Subscriber<? super SandboxFrame> checked = Objects.requireNonNull(requestedSubscriber, "subscriber");
        boolean rejected;
        synchronized (this) {
            rejected = subscriber != null;
            if (!rejected) {
                subscriber = checked;
            }
        }
        if (rejected) {
            checked.onSubscribe(RejectedSubscription.INSTANCE);
            checked.onError(new IllegalStateException("PTY frame publisher supports one subscriber"));
            return;
        }
        checked.onSubscribe(new FrameSubscription());
        drain();
    }

    boolean emit(SandboxFrame frame) throws InterruptedException {
        synchronized (this) {
            while (!producerDone && !cancelled && queue.size() >= MAX_BUFFERED_FRAMES) {
                wait();
            }
            if (producerDone || cancelled) {
                return false;
            }
            queue.addLast(Objects.requireNonNull(frame, "frame"));
        }
        drain();
        return true;
    }

    void complete() {
        finish(null);
    }

    void fail(Throwable cause) {
        finish(Objects.requireNonNull(cause, "cause"));
    }

    private void finish(Throwable cause) {
        synchronized (this) {
            if (producerDone || cancelled) {
                return;
            }
            producerDone = true;
            failure = cause;
            notifyAll();
        }
        drain();
    }

    private void drain() {
        synchronized (this) {
            if (draining) {
                return;
            }
            draining = true;
        }
        while (deliverOne()) {
            // 每次回调后重新读取 demand 和终态，避免在锁内调用外部代码。
        }
    }

    private boolean deliverOne() {
        Delivery delivery = nextDelivery();
        if (delivery == null) {
            return false;
        }
        try {
            delivery.deliver();
            if (delivery.terminal() != Terminal.NONE) {
                stopDraining();
                return false;
            }
            return true;
        } catch (RuntimeException subscriberFailure) {
            cancelSubscriber();
            return false;
        }
    }

    private Delivery nextDelivery() {
        synchronized (this) {
            if (cancelled || subscriber == null) {
                draining = false;
                return null;
            }
            if (demand > 0 && !queue.isEmpty()) {
                demand--;
                SandboxFrame frame = queue.removeFirst();
                notifyAll();
                return new Delivery(subscriber, frame, Terminal.NONE, null);
            }
            if (producerDone && queue.isEmpty() && !terminalDelivered) {
                terminalDelivered = true;
                Terminal terminal = failure == null ? Terminal.COMPLETE : Terminal.FAILURE;
                return new Delivery(subscriber, null, terminal, failure);
            }
            draining = false;
            return null;
        }
    }

    private synchronized void stopDraining() {
        draining = false;
    }

    private record Delivery(
            Flow.Subscriber<? super SandboxFrame> subscriber,
            SandboxFrame frame,
            Terminal terminal,
            Throwable failure) {
        private void deliver() {
            if (terminal == Terminal.NONE) {
                subscriber.onNext(frame);
            } else if (terminal == Terminal.COMPLETE) {
                subscriber.onComplete();
            } else {
                subscriber.onError(failure);
            }
        }
    }

    private void cancelSubscriber() {
        synchronized (this) {
            cancelled = true;
            queue.clear();
            draining = false;
            notifyAll();
        }
    }

    private final class FrameSubscription implements Flow.Subscription {
        @Override
        public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("reactive demand must be positive"));
                return;
            }
            synchronized (PtyFramePublisher.this) {
                if (cancelled || terminalDelivered) {
                    return;
                }
                demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
            }
            drain();
        }

        @Override
        public void cancel() {
            cancelSubscriber();
        }
    }

    private enum Terminal {
        NONE,
        COMPLETE,
        FAILURE
    }

    private enum RejectedSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
