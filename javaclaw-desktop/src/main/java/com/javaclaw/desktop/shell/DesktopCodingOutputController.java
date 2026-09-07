package com.javaclaw.desktop.shell;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import com.javaclaw.client.extension.CodingExecutionPoller;

/** 会话输出的单在途读取控制器；所有调用与完成回调均在 UI 调度器执行。 */
public final class DesktopCodingOutputController {
    private final Function<CodingExecutionPoller, CompletionStage<CodingExecutionPoller.Poll>> gateway;
    private final Consumer<State> render;
    private Optional<Binding> binding = Optional.empty();
    private CodingExecutionPoller poller;
    private long epoch;
    private boolean pending;

    /**
     * 创建不依赖 JavaFX 控件的异步控制器。
     *
     * @param gateway 在后台读取、在 UI 调度器完成的 SDK 边界
     * @param render 安全输出快照的展示回调
     */
    public DesktopCodingOutputController(
            Function<CodingExecutionPoller, CompletionStage<CodingExecutionPoller.Poll>> gateway,
            Consumer<State> render) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.render = Objects.requireNonNull(render, "render");
    }

    /**
     * 切换作用域或连接。旧读取可以完成，但不得修改新会话的游标或界面。
     *
     * @param next 当前连接与作用域；空值停止观察
     */
    public void bind(Optional<Binding> next) {
        Objects.requireNonNull(next, "next");
        if (next.equals(binding)) {
            return;
        }
        binding = next;
        epoch++;
        poller = next.map(value -> new CodingExecutionPoller(value.scope())).orElse(null);
        render.accept(new State(List.of(), Optional.empty()));
    }

    /** 发起一次有界读取；重复时钟事件不会为同一作用域叠加请求。 */
    public void poll() {
        if (poller == null || pending) {
            return;
        }
        pending = true;
        long requestEpoch = epoch;
        try {
            gateway.apply(poller).whenComplete((result, failure) -> complete(requestEpoch, result, failure));
        } catch (RuntimeException failure) {
            complete(requestEpoch, null, failure);
        }
    }

    private void complete(long requestEpoch, CodingExecutionPoller.Poll result, Throwable failure) {
        pending = false;
        if (requestEpoch != epoch) {
            return;
        }
        if (failure != null) {
            render.accept(new State(List.of(), Optional.of("执行输出暂不可读取；仍可在会话中取消当前 Turn。")));
        } else {
            render.accept(new State(result.snapshots(), Optional.empty()));
        }
    }

    /**
     * 与已连接实例绑定的读取过滤；不是权限凭据。
     *
     * @param scope Workspace 与可选 Thread、Turn
     * @param connectedAt 当前连接的建立时间
     */
    public record Binding(CodingExecutionPoller.Scope scope, Instant connectedAt) {
        /** 校验完整绑定。 */
        public Binding {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(connectedAt, "connectedAt");
        }
    }

    /**
     * 一次有界的展示快照。
     *
     * @param snapshots 最近执行的安全文本尾部
     * @param failure 固定、脱敏的读取失败说明
     */
    public record State(List<CodingExecutionPoller.Snapshot> snapshots, Optional<String> failure) {
        /** 固定不可变展示数据。 */
        public State {
            snapshots = List.copyOf(snapshots);
            failure = Objects.requireNonNull(failure, "failure");
        }
    }
}
