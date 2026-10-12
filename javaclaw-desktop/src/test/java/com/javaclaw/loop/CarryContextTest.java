package com.javaclaw.loop;

import com.javaclaw.loop.model.CarryForwardMode;
import com.javaclaw.loop.model.IterationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接力上下文测试：钉死「失败轮不污染接力」——纯文本目标的草稿只活在每轮 finalReply 里，
 * 一次瞬时超时若把 lastOutput 覆盖为空串，下一轮提示词中此前成果全文蒸发。
 */
class CarryContextTest {

    @Test
    void 失败轮不覆盖末轮产出_接力上下文保留正常成果() {
        CarryContext ctx = new CarryContext("反复打磨文稿", CarryForwardMode.SUMMARY);
        ctx.record(IterationResult.ok("完整草稿全文……", 0L, 0L, List.of()));
        // 下一轮超时失败：不得把 lastOutput 清空、不得追加空简述
        ctx.record(IterationResult.failed());

        assertEquals("完整草稿全文……", ctx.lastOutput(), "失败轮不得覆盖末轮正常产出");
        assertTrue(ctx.transcript().contains("完整草稿全文……"), "接力文本仍应包含草稿全文");
    }

    @Test
    void 首轮即失败_下一轮按真实轮次组装且不谎称首轮() {
        CarryContext ctx = new CarryContext("目标", CarryForwardMode.SUMMARY);
        ctx.record(IterationResult.failed());
        // 无任何有效产出：第 2 轮不得退回「开始第 1 轮」提示词（与 UI 显示的第 2 轮矛盾），
        // 应按真实轮次组装并如实告知此前无有效产出
        String second = ctx.assemble(2);
        assertNotEquals(ctx.assemble(1), second);
        assertTrue(second.contains("第 2 轮"), "提示词轮次应与控制器/UI 一致");
        assertTrue(second.contains("未产生有效产出"), "应如实告知此前轮次无有效产出");
    }
}
