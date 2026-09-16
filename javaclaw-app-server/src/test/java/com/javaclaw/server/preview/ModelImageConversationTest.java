package com.javaclaw.server.preview;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.NativeConversationSupport;
import com.javaclaw.runtime.ProviderState;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderStateService;
import com.javaclaw.server.turn.H2ConversationContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实持久历史进入图片与文本模型的兼容验证，Gateway 只提供能力且禁止模型调用。 */
class ModelImageConversationTest extends BrowserImageHistoryFixture {
    @Test
    void 用户图片和成功浏览器截图共享持久来源且文本模型只接收文本() throws Exception {
        ImageFixture fixture = image();
        append(
                fixture.source(),
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(
                        MessageRole.USER, "附图", List.of(fixture.image().attachment()), Optional.empty()));
        call(fixture, "screen", com.javaclaw.api.ItemStatus.COMPLETED);
        result(fixture, "screen", true);
        var command = command(fixture);
        var visual = context(new CapabilitiesOnly(true)).assemble(command, new CancellationSource());
        var images = visual.messages().stream()
                .flatMap(message -> message.images().stream())
                .toList();
        assertEquals(2, images.size());
        for (var image : images) {
            assertArrayEquals(
                    fixture.png(), resolver().forTurn(fixture.source().turnId()).resolve(image));
        }
        var text = context(new CapabilitiesOnly(false)).assemble(command, new CancellationSource());
        assertTrue(text.messages().stream().allMatch(message -> message.images().isEmpty()));
        assertEquals(
                visual.messages().stream().map(ModelMessage::text).toList(),
                text.messages().stream().map(ModelMessage::text).toList());
        assertTrue(visual.estimatedInputTokens() > text.estimatedInputTokens());
    }

    @Test
    void 失败工具与非Site同名输出均不能进入视觉上下文() throws Exception {
        ImageFixture fixture = image();
        call(fixture, "failed", com.javaclaw.api.ItemStatus.COMPLETED);
        result(fixture, "failed", false);
        append(
                fixture.source(),
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall("other", "external.extension", "browser_screenshot", 1, json.parse("{}")));
        result(fixture, "other", true);
        var window = context(new CapabilitiesOnly(true)).assemble(command(fixture), new CancellationSource());
        assertEquals(
                2,
                window.messages().stream()
                        .filter(message -> message.role() == MessageRole.TOOL)
                        .count());
        assertTrue(
                window.messages().stream().allMatch(message -> message.images().isEmpty()));
    }

    @Test
    void 旧Provider续接恢复使用可信图片历史且原始持久状态不被重写() throws Exception {
        ImageFixture fixture = image();
        call(fixture, "screen", com.javaclaw.api.ItemStatus.COMPLETED);
        result(fixture, "screen", true);
        var command = command(fixture);
        var saved = new ProviderState("openai", "responses-state-v2", json.parse("{\"old\":true}"));
        new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock)
                .saveProviderState(fixture.source().turnId(), command.modelRoute(), saved, 123);
        var model = new RecoveryOnly();
        var window = context(model).assemble(command, new CancellationSource());
        assertTrue(window.messages().isEmpty());
        assertEquals(
                1,
                model.covered.stream()
                        .flatMap(message -> message.images().stream())
                        .count());
        assertEquals(fixture.image(), model.covered.getLast().images().getFirst());
        assertEquals("restored-v3", window.providerState().orElseThrow().format());
        assertEquals(
                saved,
                new ProviderStateService(database)
                        .latest(
                                command.turn().threadId(),
                                command.modelRoute(),
                                command.turn().promptManifestDigest())
                        .orElseThrow()
                        .state());
        assertThrows(
                PersistenceException.class,
                () -> context(new CapabilitiesOnly(true)).assemble(command, new CancellationSource()));
    }

    private H2ConversationContext context(ModelGateway model) {
        return new H2ConversationContext(
                core, new ProviderStateService(database), CoreItemCodecs.createRegistry(json), model, resolver());
    }

    private TurnExecutionCommand command(ImageFixture fixture) {
        var turn = core.findTurn(fixture.source().turnId()).orElseThrow();
        var permissions = permission(1, List.of(workspace.root()));
        var catalog = new ToolCatalogSnapshot(turn.id(), 1, List.of(), permissions, clock.instant());
        return new TurnExecutionCommand(turn, turn.provider(), "system", "观察页面", permissions, catalog);
    }

    private static class CapabilitiesOnly implements ModelGateway {
        private final boolean images;

        CapabilitiesOnly(boolean images) {
            this.images = images;
        }

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(true, true, false, images, false, true, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            throw new AssertionError("历史回放不能调用模型");
        }
    }

    private static final class RecoveryOnly extends CapabilitiesOnly implements NativeConversationSupport {
        private List<ModelMessage> covered = List.of();

        RecoveryOnly() {
            super(true);
        }

        @Override
        public ProviderState restoreCoveredState(String modelId, ProviderState state, List<ModelMessage> messages) {
            covered = List.copyOf(messages);
            return new ProviderState(state.providerId(), "restored-v3", state.payload());
        }

        @Override
        public ModelInvocationResult invokeContinuing(
                TurnId turnId,
                ModelInvocation invocation,
                ProviderState state,
                ModelEventSink events,
                CancellationToken cancellation) {
            throw new AssertionError("历史回放不能发起续接模型调用");
        }
    }
}
