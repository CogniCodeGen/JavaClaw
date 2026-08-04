package com.javaclaw.memory.correction;

import com.javaclaw.memory.model.CorrectionRecord;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.store.MemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionEngineTest {

    @Test
    void 项目纠错会同步废弃旧事实并写入用户断言(@TempDir Path dir) {
        try (MemoryStore store = open(dir)) {
            Fact old = new Fact("项目背景", "项目使用 npm 管理依赖", vectorFor("npm"));
            store.addFact(old, "test");

            CorrectionEngine engine = new CorrectionEngine(store, this::vectorFor);
            CorrectionTurnContext context = engine.prepareTurn(
                    "这个项目不是 npm，而是 pnpm",
                    "这个项目当前使用 npm 管理依赖。");

            assertEquals(CorrectionRecord.Status.ACTIVE, context.newlyApplied().status);
            assertTrue(old.superseded);
            assertFalse(old.contested);
            assertTrue(store.allFacts().stream().anyMatch(f ->
                    f.userAsserted
                            && "USER_EXPLICIT_CORRECTION".equals(f.sourceKind)
                            && f.text.contains("pnpm")));
            assertTrue(context.toPrompt().contains("有误"));
            assertTrue(context.toPrompt().contains("为准"));
            assertTrue(context.toPrompt().contains("pnpm"));
            // 注入是背景证据而非最高优先级命令
            assertFalse(context.toPrompt().contains("priority=\"highest\""));

            assertTrue(store.searchFacts(vectorFor("npm"), 5, 0.1).stream()
                    .noneMatch(hit -> hit.entity() == old));
        }
    }

    @Test
    void 公共事实纠错只标争议不会未经核验写成有效事实(@TempDir Path dir) {
        try (MemoryStore store = open(dir)) {
            Fact old = new Fact("其它", "某公共事实的旧结论", vectorFor("旧结论"));
            store.addFact(old, "test");

            CorrectionEngine engine = new CorrectionEngine(store, this::vectorFor);
            CorrectionTurnContext context = engine.prepareTurn(
                    "某公共事实不是旧结论，而是另一个结论",
                    "某公共事实的旧结论");

            assertEquals(CorrectionRecord.Status.DISPUTED, context.newlyApplied().status);
            assertTrue(old.contested);
            assertFalse(old.superseded);
            assertTrue(store.allFacts().stream().noneMatch(f -> f.userAsserted));
            assertTrue(context.toPrompt().contains("尚未核验"));
        }
    }

    @Test
    void 后续显式纠错可以取代先前用户纠错(@TempDir Path dir) {
        try (MemoryStore store = open(dir)) {
            CorrectionEngine engine = new CorrectionEngine(store, this::vectorFor);
            CorrectionRecord first = engine.prepareTurn(
                    "这个项目不是 npm，而是 pnpm", "项目使用 npm").newlyApplied();
            CorrectionRecord second = engine.prepareTurn(
                    "这个项目不是 pnpm，而是 yarn", "项目使用 pnpm").newlyApplied();

            assertEquals(CorrectionRecord.Status.REVOKED, first.status);
            assertEquals(CorrectionRecord.Status.ACTIVE, second.status);
            assertTrue(store.allFacts().stream().anyMatch(
                    f -> f.userAsserted && !f.superseded && f.text.contains("yarn")));
            assertTrue(store.allFacts().stream().filter(f -> f.text.contains("pnpm"))
                    .allMatch(f -> f.superseded));
        }
    }

    @Test
    void 后一次用户纠错可取代手动保护和置顶的旧事实(@TempDir Path dir) {
        try (MemoryStore store = open(dir)) {
            Fact old = new Fact("项目背景", "这个项目使用 npm", vectorFor("npm"));
            old.userEdited = true;
            old.userAsserted = true;
            old.pinned = true;
            store.addFact(old, "user");

            CorrectionEngine engine = new CorrectionEngine(store, this::vectorFor);
            engine.prepareTurn("这个项目不是 npm，而是 pnpm", "这个项目使用 npm");

            assertTrue(old.superseded);
            assertTrue(store.allFacts().stream().anyMatch(
                    f -> !f.superseded && f.userAsserted && f.text.contains("pnpm")));
        }
    }

    @Test
    void 重复相同纠错不会因标识符包含关系废弃正确事实(@TempDir Path dir) {
        try (MemoryStore store = open(dir)) {
            CorrectionEngine engine = new CorrectionEngine(store, this::vectorFor);
            engine.prepareTurn("这个项目不是 npm，而是 pnpm", "这个项目使用 npm");
            Fact canonical = store.allFacts().stream()
                    .filter(f -> !f.superseded && f.text.contains("pnpm"))
                    .findFirst().orElseThrow();

            engine.prepareTurn("这个项目不是 npm，而是 pnpm", "这个项目使用 pnpm");

            assertFalse(canonical.superseded);
            assertEquals(1, store.allFacts().stream()
                    .filter(f -> !f.superseded && f.text.contains("pnpm")).count());
        }
    }

    @Test
    void 模型蒸馏不能在纠错后重新写回旧错误(@TempDir Path dir) {
        try (MemoryStore store = open(dir)) {
            CorrectionEngine engine = new CorrectionEngine(store, this::vectorFor);
            engine.prepareTurn("这个项目不是 npm，而是 pnpm", "这个项目使用 npm");

            store.addFact(new Fact("项目背景", "项目继续使用 npm", vectorFor("npm")),
                    "distiller");

            assertTrue(store.allFacts().stream().noneMatch(f ->
                    !f.superseded && !f.contested
                            && CorrectionGuard.containsClaim(f.text, "npm")));
            assertTrue(store.recentChangeLog(10).stream().anyMatch(
                    e -> "BLOCK_REINTRODUCE_ERROR".equals(e.op)));
        }
    }

    @Test
    void 纠错前暂存的旧错误在重嵌入时也不会复活(@TempDir Path dir) {
        try (MemoryStore store = open(dir)) {
            Fact pending = new Fact("项目背景", "项目继续使用 npm", null);
            pending.sourceKind = "DISTILLED";
            store.addPendingFact(pending, "distiller");

            CorrectionEngine engine = new CorrectionEngine(store, this::vectorFor);
            engine.prepareTurn("这个项目不是 npm，而是 pnpm", "这个项目使用 npm");

            assertFalse(store.promotePendingFact(pending, vectorFor("npm"), "system"));
            assertTrue(store.allPendingFacts().isEmpty());
            assertTrue(store.allFacts().stream().noneMatch(f ->
                    !f.superseded && !f.contested
                            && CorrectionGuard.containsClaim(f.text, "npm")));
        }
    }

    @Test
    void 纠错及身份索引跨重启后仍可更新(@TempDir Path dir) {
        try (MemoryStore firstStore = open(dir)) {
            CorrectionEngine engine = new CorrectionEngine(firstStore, this::vectorFor);
            engine.prepareTurn("这个项目不是 npm，而是 pnpm", "这个项目使用 npm");
        }

        try (MemoryStore reopened = open(dir)) {
            CorrectionRecord first = reopened.allCorrections().getFirst();
            CorrectionEngine engine = new CorrectionEngine(reopened, this::vectorFor);
            engine.prepareTurn("这个项目不是 pnpm，而是 yarn", "这个项目使用 pnpm");

            assertEquals(CorrectionRecord.Status.REVOKED, first.status);
            assertTrue(reopened.allFacts().stream().anyMatch(
                    f -> !f.superseded && f.text.contains("yarn")));
        }
    }

    @Test
    void 报错抱怨不得连带废弃上一轮复述过的真实事实(@TempDir Path dir) {
        try (MemoryStore store = open(dir)) {
            Fact truth = new Fact("项目背景", "项目使用 Maven 构建", vectorFor("Maven"));
            store.addFact(truth, "distiller");

            CorrectionEngine engine = new CorrectionEngine(store, this::vectorFor);
            // 用户只是在报 bug，上一轮回复恰好逐字复述过这条真实事实
            CorrectionTurnContext context = engine.prepareTurn(
                    "编译的时候报错了，帮我看看",
                    "项目使用 Maven 构建，所以先执行 mvn clean compile。");

            // ① 不产生任何持久记录
            assertTrue(store.allCorrections().isEmpty());
            assertEquals(null, context.newlyApplied());
            // ② 不碰事实状态
            assertFalse(truth.contested);
            assertFalse(truth.superseded);
            // ③ 事实仍可被召回
            assertFalse(store.searchFacts(vectorFor("Maven"), 5, 0.1).isEmpty());
            // ④ 蒸馏仍可正常写入同主题事实（未被闸门永久阻断）
            store.addFact(new Fact("项目背景", "项目使用 Maven 构建依赖", vectorFor("Maven")),
                    "distiller");
            assertEquals(2, store.allFacts().stream()
                    .filter(f -> !f.superseded && !f.contested && f.text.contains("Maven"))
                    .count());
        }
    }

    @Test
    void 纠错不会把目标事实正文回填成被否定的旧主张(@TempDir Path dir) {
        try (MemoryStore store = open(dir)) {
            Fact old = new Fact("项目背景", "这个项目使用 npm 管理依赖", vectorFor("npm"));
            store.addFact(old, "test");

            CorrectionEngine engine = new CorrectionEngine(store, this::vectorFor);
            CorrectionRecord record = engine.prepareTurn(
                    "这个项目不是 npm，而是 pnpm", "这个项目使用 npm 管理依赖").newlyApplied();

            // wrongClaim 必须只保留用户实际说出的主张，不能变成整条事实正文
            assertEquals("npm", record.wrongClaim);
            assertEquals(old.id, record.targetFactId);
            assertTrue(old.superseded);
        }
    }

    @Test
    void 无关问题不因十分钟近因而携带纠错守卫() {
        CorrectionRecord record = new CorrectionRecord();
        record.id = "recent";
        record.status = CorrectionRecord.Status.ACTIVE;
        record.scope = CorrectionRecord.Scope.PROJECT;
        record.type = CorrectionRecord.Type.FACT_REPLACEMENT;
        record.wrongClaim = "npm";
        record.correctClaim = "pnpm";
        record.sourceInput = "这个项目不是 npm，而是 pnpm";
        record.createdAt = System.currentTimeMillis();

        assertTrue(CorrectionEngine.selectRelevant(
                List.of(record), "今天天气怎么样", 6).isEmpty());
        assertFalse(CorrectionEngine.selectRelevant(
                List.of(record), "那应该怎么配置", 6).isEmpty());
        assertFalse(CorrectionEngine.selectRelevant(
                List.of(record), "npm 还能用吗", 6).isEmpty());
    }

    @Test
    void 密码纠错在任何持久化之前被拒绝(@TempDir Path dir) {
        try (MemoryStore store = open(dir)) {
            CorrectionEngine engine = new CorrectionEngine(store, this::vectorFor);

            CorrectionTurnContext context = engine.prepareTurn(
                    "我的密码不是 oldSecret123，而是 newSecret456",
                    "请使用旧密码 oldSecret123 登录");

            assertEquals(null, context.newlyApplied());
            assertTrue(context.corrections().isEmpty());
            assertTrue(store.allCorrections().isEmpty());
            assertTrue(store.allFacts().isEmpty());
            assertTrue(store.allPendingFacts().isEmpty());
        }
    }

    private static MemoryStore open(Path dir) {
        MemoryStore store = new MemoryStore(dir, 4, "correction-test");
        store.open();
        return store;
    }

    private float[] vectorFor(String text) {
        String value = text == null ? "" : text.toLowerCase();
        if (value.contains("yarn")) return new float[]{0, 0, 1, 0};
        if (value.contains("pnpm") || value.contains("另一个")) return new float[]{0, 1, 0, 0};
        if (value.contains("npm") || value.contains("旧结论")) return new float[]{1, 0, 0, 0};
        return new float[]{0, 0, 0, 1};
    }
}
