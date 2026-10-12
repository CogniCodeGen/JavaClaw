package com.javaclaw.desktop.service;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** One pending item per subscriber; a slow observer always receives the newest frame. */
public final class LatestPublisher<T> implements Flow.Publisher<T>, AutoCloseable {
    private final CopyOnWriteArrayList<Slot> subscribers = new CopyOnWriteArrayList<>();
    private final Executor executor;
    private volatile boolean closed;

    public LatestPublisher(Executor executor) { this.executor = Objects.requireNonNull(executor); }

    @Override public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber);
        Slot slot = new Slot(subscriber);
        if (closed) {
            subscriber.onSubscribe(slot);
            subscriber.onComplete();
            return;
        }
        subscribers.add(slot);
        subscriber.onSubscribe(slot);
        if (closed) slot.complete();
    }

    public void submit(T item) {
        Objects.requireNonNull(item);
        if (closed) return;
        for (Slot subscriber : subscribers) subscriber.offer(item);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Slot subscriber : subscribers) subscriber.complete();
        subscribers.clear();
    }

    private final class Slot implements Flow.Subscription {
        private final Flow.Subscriber<? super T> subscriber;
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private T pending;
        private long demand;
        private boolean cancelled;

        Slot(Flow.Subscriber<? super T> subscriber) { this.subscriber = subscriber; }

        synchronized void offer(T item) {
            if (cancelled) return;
            pending = item;
            dispatch();
        }

        @Override public synchronized void request(long count) {
            if (count <= 0) {
                cancel();
                subscriber.onError(new IllegalArgumentException("non-positive demand"));
                return;
            }
            demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
            dispatch();
        }

        @Override public synchronized void cancel() {
            cancelled = true;
            pending = null;
            subscribers.remove(this);
        }

        void complete() {
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                pending = null;
            }
            subscribers.remove(this);
            executor.execute(subscriber::onComplete);
        }

        private void dispatch() {
            if (pending != null && demand > 0 && !cancelled && scheduled.compareAndSet(false, true))
                executor.execute(this::drain);
        }

        private void drain() {
            try {
                while (true) {
                    T item;
                    synchronized (this) {
                        if (cancelled || demand == 0 || pending == null) return;
                        item = pending;
                        pending = null;
                        demand--;
                    }
                    try { subscriber.onNext(item); }
                    catch (Throwable failure) {
                        cancel();
                        subscriber.onError(failure);
                        return;
                    }
                }
            } finally {
                scheduled.set(false);
                synchronized (this) { dispatch(); }
            }
        }
    }
}
