package com.javaclaw.server.preview;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemStatus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 图片字节读取必须查证唯一先行调用，不能因历史顺序或重复身份扩大模型附件权限。 */
class BrowserImageHistoryTest extends BrowserImageHistoryFixture {
    @Test
    void 唯一已完成浏览器调用允许读取但结果之后的重复身份撤销图片资格() throws Exception {
        ImageFixture fixture = image();
        call(fixture, "same", ItemStatus.COMPLETED);
        result(fixture, "same", true);
        assertArrayEquals(fixture.png(), resolver().resolve(fixture.image()));
        call(fixture, "same", ItemStatus.COMPLETED);
        assertThrows(SecurityException.class, () -> resolver().resolve(fixture.image()));
    }

    @Test
    void 后到调用和未完成调用以及失败结果都不能签发图片权限() throws Exception {
        ImageFixture late = image();
        result(late, "late", true);
        call(late, "late", ItemStatus.COMPLETED);
        assertThrows(SecurityException.class, () -> resolver().resolve(late.image()));

        ImageFixture pending = image();
        call(pending, "pending", ItemStatus.IN_PROGRESS);
        result(pending, "pending", true);
        assertThrows(SecurityException.class, () -> resolver().resolve(pending.image()));

        ImageFixture failed = image();
        call(failed, "failed", ItemStatus.COMPLETED);
        result(failed, "failed", false);
        assertThrows(SecurityException.class, () -> resolver().resolve(failed.image()));
    }
}
