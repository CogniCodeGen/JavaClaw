package com.javaclaw.client.cli;

import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.InputRequestRecord;

/**
 * 展示给人的精确请求版本；正文来自权威读取，epoch 仅用于本地输入归属。
 *
 * @param epoch 本地单调递增的展示代次，无单位，用于拒绝迟到答案
 * @param approval 精确审批快照；输入请求时为空，与 input 恰有一个非空
 * @param input 精确结构化输入快照；审批请求时为空，与 approval 恰有一个非空
 */
record CliPendingInteraction(long epoch, ApprovalRecord approval, InputRequestRecord input) {
    boolean current(List<ApprovalRecord> approvals, List<InputRequestRecord> inputs, Instant now) {
        return approval != null
                ? approvals.stream()
                        .anyMatch(value -> value.equals(approval)
                                && value.pending()
                                && now.isBefore(value.request().expiresAt()))
                : inputs.stream()
                        .anyMatch(value -> value.equals(input)
                                && value.pending()
                                && now.isBefore(value.request().expiresAt()));
    }

    String id() {
        return approval != null ? approval.request().id() : input.request().id();
    }

    void show(PrintStream output) {
        if (approval != null) {
            var request = approval.request();
            output.println("等待审批 " + request.id() + "，revision=" + approval.revision());
            output.println("Turn=" + request.turnId() + "，工具=" + request.tool());
            output.println("风险=" + request.risk() + "；" + request.explanation());
            output.println("参数摘要=" + request.requestDigest() + "；有效期至 " + request.expiresAt());
            output.println("输入 approve 明确批准，deny 明确拒绝，或 /cancel 取消 Turn；空行不会批准。");
        } else {
            var request = input.request();
            output.println("等待输入 " + request.id() + "，revision=" + input.revision());
            output.println("来源=" + request.producerId() + "；有效期至 " + request.expiresAt());
            output.println(request.prompt());
            output.println("响应 Schema：" + request.responseSchema().json());
            output.println("输入一行符合受限 Schema 的 JSON 对象（最多 64 KiB），或 /cancel 取消 Turn。");
        }
    }
}
