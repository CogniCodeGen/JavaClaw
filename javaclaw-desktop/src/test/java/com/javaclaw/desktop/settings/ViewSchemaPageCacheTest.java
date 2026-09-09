package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.FakeGateway;
import com.javaclaw.desktop.view.ViewData;

import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.data;
import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.field;
import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.page;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaPageCacheTest {
    @Test
    void 重复打开同一页面复用成功数据Schema与原生控件() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("已有内容", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            TextField editor = field(page.content());
            editor.selectRange(1, 3);
            for (int index = 0; index < 5; index++) {
                page.deactivate();
                page.activate();
            }
            assertEquals(1, gateway.catalogs);
            assertEquals(1, gateway.loads);
            assertSame(editor, field(page.content()));
            assertEquals(1, editor.getAnchor());
            assertEquals(3, editor.getCaretPosition());
            page.dispose();
        });
    }

    @Test
    void 加载中重复激活不会取消并重发相同查询() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("已有内容", 1));
            CompletableFuture<ViewData> delayed = new CompletableFuture<>();
            gateway.loadsToReturn.add(delayed);
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            page.activate();
            assertEquals(1, gateway.catalogs);
            assertEquals(1, gateway.loads);
            delayed.complete(data("查询完成", 2));
            assertEquals("查询完成", field(page.content()).getText());
            page.dispose();
        });
    }

    @Test
    void 隐藏时取消的查询不能作为成功缓存且迟到结果不能覆盖新值() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("再次打开的新值", 2));
            CompletableFuture<ViewData> delayed = new CompletableFuture<>();
            gateway.loadsToReturn.add(delayed);
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            page.deactivate();
            page.activate();
            assertEquals(1, gateway.catalogs);
            assertEquals(2, gateway.loads);
            delayed.complete(data("取消后迟到的旧值", 1));
            assertEquals("再次打开的新值", field(page.content()).getText());
            page.dispose();
        });
    }

    @Test
    void 重连失效在隐藏时不发请求并在下次打开重读目录和数据() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("重连前", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            page.deactivate();
            page.invalidateCache();
            page.invalidateCache();
            assertEquals(1, gateway.catalogs);
            assertEquals(1, gateway.loads);
            gateway.authoritative = data("重连后", 2);
            page.activate();
            assertEquals(2, gateway.catalogs);
            assertEquals(2, gateway.loads);
            assertEquals("重连后", field(page.content()).getText());
            page.dispose();
        });
    }

    @Test
    void 重连时已在途查询不能重新填充旧缓存() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("重连后的值", 2));
            CompletableFuture<ViewData> delayed = new CompletableFuture<>();
            gateway.loadsToReturn.add(delayed);
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            page.invalidateCache();
            delayed.complete(data("旧连接的值", 1));
            page.activate();
            assertEquals(2, gateway.catalogs);
            assertEquals(2, gateway.loads);
            assertEquals("重连后的值", field(page.content()).getText());
            page.dispose();
        });
    }

    @Test
    void 重连失效不能覆盖草稿且显式丢弃后使用新目录() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("原值", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            TextField editor = field(page.content());
            editor.setText("必须保留的草稿");
            page.deactivate();
            page.invalidateCache();
            page.activate();
            assertTrue(page.dirty());
            assertEquals("必须保留的草稿", editor.getText());
            assertEquals(1, gateway.loads);
            page.discardDraft();
            assertEquals(2, gateway.catalogs);
            assertEquals(2, gateway.loads);
            assertFalse(page.dirty());
            assertEquals("原值", field(page.content()).getText());
            page.dispose();
        });
    }

    @Test
    void 动态页面成功缓存十五秒且失败取消和重连都不能延长有效期() {
        AtomicLong clock = new AtomicLong();
        ViewPageCacheState cache = new ViewPageCacheState(clock::get);
        assertFalse(cache.fresh());
        assertTrue(cache.requiresCatalog());
        cache.catalogLoaded();
        cache.loaded();
        assertFalse(cache.requiresCatalog());
        clock.set(Duration.ofSeconds(15).toNanos() - 1);
        assertTrue(cache.fresh());
        clock.incrementAndGet();
        assertFalse(cache.fresh());
        cache.loaded();
        cache.loading();
        assertFalse(cache.fresh());
        cache.loaded();
        cache.invalidate();
        assertFalse(cache.fresh());
        assertTrue(cache.requiresCatalog());
    }
}
