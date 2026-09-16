package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderImageSupport;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformComponentFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ProviderImageSupportTest {
    @Test
    void 新发现默认未知且选择模型前的图片声明会进入最终配置() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            assertEquals(ProviderImageSupport.UNKNOWN, images(form).getValue());
            images(form).setValue(ProviderImageSupport.SUPPORTED);
            choice(form).fire();
            assertEquals(
                    ProviderImageSupport.SUPPORTED,
                    form.selectedModels().getFirst().imageSupport());
            assertEquals("model", form.currentModel());
        });
    }

    @Test
    void 搜索与再次发现不会覆盖用户声明且更新当前模型保持同一选择() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            choice(form).fire();
            images(form).setValue(ProviderImageSupport.SUPPORTED);
            ((TextField) form.lookup("#providerWizardSearch")).setText("missing");
            form.candidates(List.of(candidate()));
            ((TextField) form.lookup("#providerWizardSearch")).clear();
            assertEquals(ProviderImageSupport.SUPPORTED, images(form).getValue());
            images(form).setValue(ProviderImageSupport.UNSUPPORTED);
            assertEquals(
                    ProviderImageSupport.UNSUPPORTED,
                    form.selectedModels().getFirst().imageSupport());
            assertEquals("model", form.currentModel());
            assertEquals("未知", ProviderImageSupportField.label(ProviderImageSupport.UNKNOWN));
            assertEquals("支持", ProviderImageSupportField.label(ProviderImageSupport.SUPPORTED));
            assertEquals("不支持", ProviderImageSupportField.label(ProviderImageSupport.UNSUPPORTED));
        });
    }

    @Test
    void 仅图片声明变化也生成不同的保存幂等键() {
        var unknown = ProviderSetupCommands.finish(
                "provider", 2, spec(ProviderImageSupport.UNKNOWN), ProviderLifecycle.ACTIVE);
        var supported = ProviderSetupCommands.finish(
                "provider", 2, spec(ProviderImageSupport.SUPPORTED), ProviderLifecycle.ACTIVE);
        var unsupported = ProviderSetupCommands.finish(
                "provider", 2, spec(ProviderImageSupport.UNSUPPORTED), ProviderLifecycle.ACTIVE);
        assertNotEquals(unknown.idempotencyKey(), supported.idempotencyKey());
        assertNotEquals(unknown.idempotencyKey(), unsupported.idempotencyKey());
        assertNotEquals(supported.idempotencyKey(), unsupported.idempotencyKey());
    }

    private static ProviderSetupModelForm form() {
        ProviderSetupModelForm form = new ProviderSetupModelForm(new PlatformComponentFactory(), () -> {});
        new Scene(form, 620, 400);
        form.candidates(List.of(candidate()));
        form.applyCss();
        form.layout();
        return form;
    }

    private static ProviderImageSupportField images(ProviderSetupModelForm form) {
        return (ProviderImageSupportField) form.lookup(".provider-image-support");
    }

    private static CheckBox choice(ProviderSetupModelForm form) {
        return (CheckBox) form.lookup(".check-box");
    }

    private static ProviderModelDiscoveryCandidate candidate() {
        return new ProviderModelDiscoveryCandidate(
                "model", "Model", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty());
    }

    private static ProviderEndpointSpec spec(ProviderImageSupport support) {
        ProviderModelSpec model = new ProviderModelSpec(
                "model", "Model", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty(), support);
        return new ProviderEndpointSpec(
                "Local",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:11434/v1")),
                ProviderAuthentication.NONE,
                List.of(model),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }
}
