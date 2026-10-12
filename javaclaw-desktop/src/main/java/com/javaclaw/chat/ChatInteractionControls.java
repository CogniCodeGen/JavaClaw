package com.javaclaw.chat;

import com.javaclaw.api.conversation.*;
import com.javaclaw.framework.api.InteractionControlCommand;
import com.javaclaw.platform.fx.FxDispatcher;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** 委派任务的人类指令与只读状态投影；迟到结果不得改动已切换的轮次或会话。 */
final class ChatInteractionControls {
    record Context(Supplier<ChatActiveTurn> turn, Supplier<ChatSession> session,
                   BooleanSupplier streaming, BooleanSupplier rebuilding, IntSupplier generation) { }

    private final FxDispatcher fx;
    private final ChatComposerController composer;
    private final ChatModeController modeBar;
    private final Supplier<ModeRegistry> modes;
    private final ChatTurnHost host;
    private final Context context;

    ChatInteractionControls(FxDispatcher fx, ChatComposerController composer, ChatModeController modeBar,
                            Supplier<ModeRegistry> modes, ChatTurnHost host, Context context) {
        this.fx = fx;
        this.composer = composer;
        this.modeBar = modeBar;
        this.modes = modes;
        this.host = host;
        this.context = context;
        composer.setOnInteractionControl(this::submit);
    }

    void consume(ConversationEvent.Custom event) {
        ChatActiveTurn turn = context.turn().get();
        if (turn == null) return;
        String kind = event.kind();
        if (kind.equals("core.run.waiting_child")
                || kind.equals("core.interaction.child_waiting_input")
                || kind.equals("core.interaction.child_waiting_approval")) {
            long revision = event.payload().path("revision").asLong(0);
            if (revision < 1) revision = event.payload().path("context").path("revision").asLong(0);
            if (revision < 1) revision = event.payload().path("output").path("revision").asLong(0);
            if (revision > 0 && revision < turn.interactionRevision) return;
            if (revision > 0) turn.interactionRevision = revision;
            turn.interactionWaiting = true;
            turn.interactionChildEventSequence = kind.equals("core.interaction.child_waiting_input")
                    || kind.equals("core.run.waiting_child")
                    ? event.payload().path("childEventSequence").asLong(0) : 0;
            turn.interactionNeedsAnswer = turn.interactionChildEventSequence > 0
                    && (kind.equals("core.interaction.child_waiting_input")
                        || kind.equals("core.run.waiting_child") && event.payload().path("needsAnswer").asBoolean(false));
            turn.interactionCommandPending = false;
        } else if (kind.equals("core.interaction.control_accepted")) {
            long revision = event.payload().path("revision").asLong(0);
            if (revision > 0 && revision < turn.interactionRevision) return;
            if (revision > 0) turn.interactionRevision = revision;
            turn.interactionCommandPending = true;
            turn.interactionNeedsAnswer = false;
            turn.interactionChildEventSequence = 0;
        } else if (kind.equals("core.interaction.amending")) {
            long revision = event.payload().path("revision").asLong(0);
            if (revision > 0 && revision < turn.interactionRevision) return;
            turn.interactionWaiting = false;
            turn.interactionNeedsAnswer = false;
            turn.interactionChildEventSequence = 0;
            turn.interactionCommandPending = true;
        } else if (kind.equals("core.interaction.control_applied")) {
            long revision = event.payload().path("revision").asLong(0);
            if (revision > 0 && revision < turn.interactionRevision) return;
            if (revision > 0) turn.interactionRevision = revision;
            turn.interactionCommandPending = false;
            if (event.payload().path("delivery").asText().equals("REJECTED")) {
                turn.interactionChildEventSequence = event.payload().path("currentChallengeSequence").asLong(0);
                turn.interactionNeedsAnswer = event.payload().path("needsAnswer").asBoolean(false)
                        && turn.interactionChildEventSequence > 0;
            }
        } else if (kind.equals("core.interaction.child_completed")
                || kind.equals("core.run.resumed")) {
            turn.interactionWaiting = false;
            turn.interactionNeedsAnswer = false;
            turn.interactionChildEventSequence = 0;
            turn.interactionCommandPending = false;
        } else return;
        renderInteractionControls(turn);
    }

    void refresh() {
        ChatActiveTurn turn = context.turn().get();
        if (turn == null) {
            composer.setInteractionWaiting(false, false, "");
            composer.setInteractionCommandPending(false);
            return;
        }
        renderInteractionControls(turn);
    }

    private void renderInteractionControls(ChatActiveTurn turn) {
        boolean waiting = turn.interactionWaiting && turn.interactionRevision > 0
                && context.session().get() == host.currentSession();
        composer.setInteractionWaiting(waiting, turn.interactionNeedsAnswer,
                turn.interactionNeedsAnswer
                        ? "交互子任务需要补充信息：请选择回答、修改要求或取消。"
                        : "交互子任务执行中：可补充修改要求，或取消子任务。 ");
        composer.setInteractionCommandPending(turn.interactionCommandPending);
    }

    private void submit(InteractionControlCommand.Type type) {
        ChatActiveTurn turn = context.turn().get();
        if (context.rebuilding().getAsBoolean() || turn == null || !context.streaming().getAsBoolean()
                || !turn.interactionWaiting || turn.interactionRevision < 1
                || turn.interactionCommandPending || context.session().get() != host.currentSession()) return;
        if (type == InteractionControlCommand.Type.ANSWER
                && (!turn.interactionNeedsAnswer || turn.interactionChildEventSequence < 1)) return;
        String text = type == InteractionControlCommand.Type.CANCEL ? "" : composer.trimmedInput();
        if (type != InteractionControlCommand.Type.CANCEL && text.isEmpty()) {
            composer.showInputError();
            return;
        }
        Mode selected = modes.get().getById(turn.modeId).orElse(null);
        if (!(selected instanceof ConversationMode mode)) return;
        final InteractionControlCommand command;
        try {
            command = new InteractionControlCommand(java.util.UUID.randomUUID().toString(),
                    turn.interactionRevision, type, text,
                    type == InteractionControlCommand.Type.ANSWER ? turn.interactionChildEventSequence : 0);
        } catch (IllegalArgumentException invalid) {
            composer.showInputError();
            host.addStaticMessage(ChatMessage.Role.SYSTEM, invalid.getMessage());
            return;
        }
        turn.interactionCommandPending = true;
        renderInteractionControls(turn);
        PlanProfile profile = "plan".equals(turn.modeId) ? modeBar.planProfile() : PlanProfile.AUTO;
        try {
            mode.start(new ConversationRequest(text, List.of(), context.session().get().getId(),
                    new ConversationOptions(profile, command), List.of()), new ConversationCallbacks() {
                @Override public void onEvent(ConversationEvent event) { }
                @Override public void onTerminal(ConversationOutcome outcome) {
                    fx.dispatch(() -> {
                        if (context.turn().get() != turn || context.generation().getAsInt() != turn.generation) return;
                        if (outcome instanceof ConversationOutcome.Failed) {
                            turn.interactionCommandPending = false;
                        }
                        if (context.session().get() != host.currentSession()) {
                            renderInteractionControls(turn);
                            return;
                        }
                        if (outcome instanceof ConversationOutcome.Failed failed) {
                            host.addStaticMessage(ChatMessage.Role.SYSTEM,
                                    "交互指令未应用：" + Objects.requireNonNullElse(failed.error().getMessage(), "请重试"));
                            composer.showInputError();
                        } else {
                            host.addUserMessage(switch (type) {
                                case ANSWER -> "回答：" + text;
                                case AMEND -> "修改要求：" + text;
                                case CANCEL -> "取消交互子任务";
                            }, List.of());
                            composer.clearInput();
                            host.saveChatHistory();
                        }
                        renderInteractionControls(turn);
                    });
                }
            });
        } catch (RuntimeException failure) {
            turn.interactionCommandPending = false;
            renderInteractionControls(turn);
            host.addStaticMessage(ChatMessage.Role.SYSTEM,
                    "交互指令未应用：" + Objects.requireNonNullElse(failure.getMessage(), "请重试"));
        }
    }

}
