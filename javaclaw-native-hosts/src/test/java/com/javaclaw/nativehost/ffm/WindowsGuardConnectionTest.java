package com.javaclaw.nativehost.ffm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsGuardConnectionTest {
    @Test
    void 客户端可核验所有者但不能创建同名服务端实例或被对端冒充() {
        assertEquals(0x20000, WindowsGuardConnection.ACCESS & 0x20000);
        assertEquals(3, WindowsGuardConnection.ACCESS & 3);
        assertEquals(0, WindowsGuardConnection.ACCESS & 4);
        assertEquals(0x100000, WindowsGuardConnection.ACCESS & 0x100000);
        assertEquals(0x40000000, WindowsGuardConnection.FLAGS & 0x40000000);
        assertEquals(0x00110000, WindowsGuardConnection.FLAGS & 0x003f0000);
    }
}
