package com.javaclaw.server.extension;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuiltinServicesCloseTest {
    @Test
    void 登录态保存失败仍关闭剩余Worker并保留原始失败证据() {
        var closed = new ArrayList<String>();
        var save = new IllegalStateException("保存失败");
        var worker = new IllegalStateException("Worker 关闭失败");
        var result = assertThrows(
                IllegalStateException.class,
                () -> BuiltinIsolatedServices.closeResources(
                        () -> {
                            closed.add("interactive");
                            throw save;
                        },
                        () -> {
                            closed.add("knowledge");
                            throw worker;
                        },
                        () -> closed.add("browser")));
        assertEquals(List.of("interactive", "knowledge", "browser"), closed);
        assertSame(save, result);
        assertSame(worker, result.getSuppressed()[0]);
    }
}
