package com.javaclaw.system;

import com.javaclaw.skill.Skill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link JShellTools#resolveScript} 脚本路径穿越/符号链接防护回归测试 */
class JShellToolsResolveScriptTest {

    private Skill skillAt(Path dir) {
        Skill skill = new Skill();
        skill.setDirectory(dir);
        return skill;
    }

    @Test
    void 正常脚本解析成功(@TempDir Path dir) throws Exception {
        Path scripts = Files.createDirectories(dir.resolve(Skill.SCRIPTS_DIR));
        Path script = Files.writeString(scripts.resolve("run.jsh"), "1+1");
        assertEquals(script, JShellTools.resolveScript(skillAt(dir), "run.jsh"));
        assertEquals(script, JShellTools.resolveScript(skillAt(dir), "scripts/run.jsh"), "允许带 scripts/ 前缀");
    }

    @Test
    void 点点穿越拒绝(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(Skill.SCRIPTS_DIR));
        Files.writeString(dir.resolve("outside.jsh"), "evil");
        assertNull(JShellTools.resolveScript(skillAt(dir), "../outside.jsh"));
    }

    @Test
    void 非脚本扩展名拒绝(@TempDir Path dir) throws Exception {
        Path scripts = Files.createDirectories(dir.resolve(Skill.SCRIPTS_DIR));
        Files.writeString(scripts.resolve("data.txt"), "x");
        assertNull(JShellTools.resolveScript(skillAt(dir), "data.txt"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void 符号链接逃逸拒绝(@TempDir Path dir) throws Exception {
        Path scripts = Files.createDirectories(dir.resolve(Skill.SCRIPTS_DIR));
        Path outside = Files.writeString(dir.resolve("evil-target.jsh"), "evil");
        Files.createSymbolicLink(scripts.resolve("evil.jsh"), outside);
        assertNull(JShellTools.resolveScript(skillAt(dir), "evil.jsh"), "指向 scripts/ 外的符号链接脚本必须拒绝");
    }
}
