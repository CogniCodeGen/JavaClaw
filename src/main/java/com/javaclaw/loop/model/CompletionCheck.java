package com.javaclaw.loop.model;

import java.util.List;

/**
 * 完成判定的核验结果。
 *
 * <p>{@code satisfied}/{@code total} 既用于「是否完成」，也作为「继续判定」里
 * 单调前进的证据、以及「无进展」的反证——一套客观谓词贯穿三个判断。</p>
 *
 * @param done      是否判定完成（执行体提议完成 且 核验通过）
 * @param satisfied 已满足的成功准则数
 * @param total     成功准则总数（无结构化准则时为 0）
 * @param missing   未满足的准则描述（供接力上下文与停止说明）
 */
public record CompletionCheck(boolean done, int satisfied, int total, List<String> missing) {

    public CompletionCheck {
        missing = missing == null ? List.of() : List.copyOf(missing);
    }
}
