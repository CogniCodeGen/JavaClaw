package com.javaclaw.client.cli;

import java.io.PrintStream;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.TurnId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolException;

/**
 * 在观察线程处理精确请求与终端答案，不在阻塞 reader 或通知回调中调用 SDK。
 *
 * <p>批准只来自显式输入；无人工宿主时只能拒绝审批。revision 冲突会使当前答案失效，禁止自动重投新版本。
 */
final class CliInteractions implements AutoCloseable {
    private final JavaClawClient client;
    private final TurnId turnId;
    private final PrintStream output;
    private final Clock clock;
    private final CanonicalJson json = new CanonicalJson();
    private final CliInputReader reader;
    private CliPendingInteraction active;
    private long epoch;

    CliInteractions(JavaClawClient client, TurnId turnId, PrintStream output, Clock clock, CliTerminal terminal) {
        this.client = client;
        this.turnId = turnId;
        this.output = output;
        this.clock = clock;
        reader = terminal.interactive() ? new CliInputReader(terminal.reader()) : null;
    }

    Optional<Stop> observe() {
        List<ApprovalRecord> approvals = client.approvals().list(Optional.of(turnId), false);
        List<InputRequestRecord> inputs = client.inputs().list(Optional.of(turnId), false);
        if (reader == null) {
            return nonInteractive(approvals, inputs);
        }
        if (active != null && !active.current(approvals, inputs, clock.instant())) {
            output.println("请求 " + active.id() + " 已变化、结束或过期；旧答案不再接受。");
            active = null;
        }
        Optional<Stop> stop = answer(reader.poll());
        if (stop.isPresent()) {
            return stop;
        }
        if (active == null && !reader.busy()) {
            next(approvals, inputs);
        }
        return Optional.empty();
    }

    private Optional<Stop> nonInteractive(List<ApprovalRecord> approvals, List<InputRequestRecord> inputs) {
        for (ApprovalRecord approval : approvals) {
            if (approval.pending()
                    && clock.instant().isBefore(approval.request().expiresAt())) {
                try {
                    ApprovalRecord result = client.approvals()
                            .resolve(
                                    approval.request().id(),
                                    ApprovalDecision.DENIED,
                                    "CLI 无人工交互宿主，明确拒绝本次工具调用",
                                    CommandOptions.create(approval.revision()));
                    output.println("无人工交互宿主，审批 " + approval.request().id() + "：" + result.state());
                } catch (RemoteRpcException failure) {
                    if (failure.code() != ProtocolErrorCode.REVISION_CONFLICT) {
                        throw failure;
                    }
                    output.println("审批状态已变化，将重新读取。");
                }
            }
        }
        return inputs.stream()
                .filter(value -> value.pending()
                        && clock.instant().isBefore(value.request().expiresAt()))
                .findFirst()
                .map(value -> {
                    output.println("必需输入 " + value.request().id() + "："
                            + value.request().prompt());
                    return new Stop(2, "CLI 无人工交互宿主，无法提供必需输入");
                });
    }

    private Optional<Stop> answer(CliInputReader.Answer answer) {
        if (answer == null) {
            return Optional.empty();
        }
        if (answer.kind() == CliInputReader.Kind.EOF) {
            return Optional.of(new Stop(130, "用户关闭终端输入"));
        }
        if (answer.kind() == CliInputReader.Kind.ERROR) {
            return Optional.of(new Stop(1, answer.text()));
        }
        if (active == null || active.epoch() != answer.epoch()) {
            output.println("已丢弃过期请求的迟到输入。");
            return Optional.empty();
        }
        if (answer.kind() == CliInputReader.Kind.LINE && answer.text().equals("/cancel")) {
            return Optional.of(new Stop(130, "用户取消 CLI Turn"));
        }
        CliPendingInteraction selected = active;
        active = null;
        submitAnswer(selected, answer);
        // 提交后必须重新读取权威状态，不能在本轮查询的旧列表上重新显示请求。
        return Optional.of(new Stop(0, "刷新"));
    }

    private void submitAnswer(CliPendingInteraction selected, CliInputReader.Answer answer) {
        try {
            if (answer.kind() == CliInputReader.Kind.TOO_LONG) {
                throw new IllegalArgumentException("输入超过 64 KiB，请重新输入");
            }
            submit(selected, answer.text());
        } catch (IllegalArgumentException | ProtocolException failure) {
            output.println("输入无效：" + failure.getMessage());
        } catch (RemoteRpcException failure) {
            if (failure.code() != ProtocolErrorCode.REVISION_CONFLICT
                    && failure.code() != ProtocolErrorCode.INVALID_PARAMS
                    && failure.code() != ProtocolErrorCode.INVALID_REQUEST) {
                throw failure;
            }
            output.println("请求未采纳：" + failure.getMessage() + "；将重新读取状态。");
        }
    }

    private void submit(CliPendingInteraction selected, String text) {
        if (selected.approval() != null) {
            ApprovalDecision decision =
                    switch (text) {
                        case "approve", "批准" -> ApprovalDecision.APPROVED;
                        case "deny", "拒绝" -> ApprovalDecision.DENIED;
                        default -> throw new IllegalArgumentException("必须明确输入 approve 或 deny；空行不会批准");
                    };
            ApprovalRecord result = client.approvals()
                    .resolve(
                            selected.id(),
                            decision,
                            "用户通过 CLI 明确" + (decision == ApprovalDecision.APPROVED ? "批准" : "拒绝"),
                            CommandOptions.create(selected.approval().revision()));
            output.println(result.state() == ApprovalState.EXPIRED ? "审批已过期，本次决议未采纳。" : "审批结果：" + result.state());
        } else {
            var response = json.parse(text);
            json.requireFlatPrimitiveObjectValue(selected.input().request().responseSchema(), response);
            InputRequestRecord result = client.inputs()
                    .resolve(
                            selected.id(),
                            response,
                            CommandOptions.create(selected.input().revision()));
            output.println(result.state() == InputRequestState.RESOLVED ? "输入已提交。" : "输入未采纳：" + result.state());
        }
    }

    private void next(List<ApprovalRecord> approvals, List<InputRequestRecord> inputs) {
        for (ApprovalRecord approval : approvals) {
            if (approval.pending()
                    && clock.instant().isBefore(approval.request().expiresAt())) {
                display(new CliPendingInteraction(++epoch, approval, null));
                return;
            }
        }
        for (InputRequestRecord input : inputs) {
            if (input.pending() && clock.instant().isBefore(input.request().expiresAt())) {
                json.requireFlatPrimitiveObjectSchema(input.request().responseSchema(), 100);
                display(new CliPendingInteraction(++epoch, null, input));
                return;
            }
        }
    }

    private void display(CliPendingInteraction value) {
        active = value;
        active.show(output);
        reader.request(value.epoch());
    }

    /** 退出时使全部输入票据失效，不关闭宿主终端。 */
    @Override
    public void close() {
        active = null;
        if (reader != null) {
            reader.close();
        }
    }

    /**
     * 交互处理后的观察动作；code 0 仅请求下一轮刷新，其他值要求取消前台 Turn。
     *
     * @param code 无单位的进程退出码；0 为继续刷新，1 为读取失败，2 为缺少必需输入，130 为用户取消
     * @param reason 刷新说明或提交给服务端的脱敏取消原因，不可空
     */
    record Stop(int code, String reason) {}
}
