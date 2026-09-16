package com.javaclaw.builtin.contracts;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodingScriptContractsTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 缺省参数不修改源码且编码往返稳定() {
        var input = json.decode(
                new CanonicalPayload("{\"source\":\"  21 * 2;\\n\"}"), CodingScriptContracts.ScriptRun.class);
        assertEquals("  21 * 2;\n", input.source());
        assertEquals(".", input.workingDirectory());
        assertEquals(30, input.timeoutSeconds());
        assertEquals(65_536, input.maxOutputBytes());
        assertEquals(input, json.decode(json.encode(input), CodingScriptContracts.ScriptRun.class));
    }

    @Test
    void 源码按UTF8字节限制并拒绝NUL空白及损坏代理字符() {
        for (String source : new String[] {" ", "\0", "\ud800", "\udc00", "中".repeat(21_846)}) {
            assertThrows(IllegalArgumentException.class, () -> new CodingScriptContracts.ScriptRun(source, ".", 30, 1));
        }
        var accepted = new CodingScriptContracts.ScriptRun("中".repeat(21_845), ".", 30, 1);
        assertEquals(21_845, accepted.source().length());
    }

    @Test
    void 执行边界不能通过路径或零预算放宽() {
        assertThrows(
                IllegalArgumentException.class, () -> new CodingScriptContracts.ScriptRun("1", "../outside", 30, 1));
        assertThrows(IllegalArgumentException.class, () -> new CodingScriptContracts.ScriptRun("1", ".", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CodingScriptContracts.ScriptRun("1", ".", 1, 1_048_577));
    }
}
