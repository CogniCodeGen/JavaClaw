package com.javaclaw.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.InputInteractionState;
import com.javaclaw.desktop.state.InteractionState;
import com.javaclaw.desktop.state.NavigationState;
import com.javaclaw.desktop.state.ThreadState;
import com.javaclaw.desktop.state.TranscriptState;
import com.javaclaw.desktop.view.PresentedItem;
import com.javaclaw.desktop.view.ViewData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopStateModelTest {
    @Test
    void connectionAndNavigationStatesEnforceLifecycleInvariants() {
        assertEquals(
                ConnectionState.Status.DISCONNECTED,
                ConnectionState.disconnected().status());
        assertEquals(
                ConnectionState.Status.CONNECTING, ConnectionState.connecting().status());
        assertEquals(
                ConnectionState.Status.FAILED, ConnectionState.failed("  失败  ").status());
        assertEquals("失败", ConnectionState.failed("  失败  ").detail());
        assertEquals(
                DesktopTestFixtures.NOW,
                ConnectionState.connected("server 5", DesktopTestFixtures.NOW)
                        .connectedAt()
                        .orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConnectionState(ConnectionState.Status.CONNECTED, "bad", Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConnectionState(ConnectionState.Status.FAILED, "bad", Optional.of(DesktopTestFixtures.NOW)));

        NavigationState initial = NavigationState.initial();
        assertTrue(initial.sidebarVisible());
        assertTrue(initial.progressVisible());
        assertFalse(initial.toggleSidebar().sidebarVisible());
        assertFalse(initial.toggleProgress().progressVisible());
        assertEquals(
                "plan",
                new NavigationState(NavigationState.Page.EXTENSION, Optional.of(" plan "), true, false)
                        .extensionViewId()
                        .orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new NavigationState(NavigationState.Page.CHAT, Optional.of("view"), true, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NavigationState(NavigationState.Page.EXTENSION, Optional.of(" "), true, true));
    }

    @Test
    void threadStateRejectsSelectionsOutsideCurrentOwnershipChain() {
        Workspace workspace = DesktopTestFixtures.workspace();
        ConversationThread thread = DesktopTestFixtures.thread(workspace);
        ThreadState valid = new ThreadState(
                List.of(workspace),
                Optional.of(workspace),
                List.of(thread),
                Optional.of(thread),
                Optional.of(DesktopTestFixtures.turn(thread, com.javaclaw.api.TurnStatus.RUNNING, 1)));

        assertEquals(thread, valid.selectedThread().orElseThrow());
        assertEquals(ThreadState.empty(), DesktopState.initial().threads());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThreadState(
                        List.of(), Optional.of(workspace), List.of(), Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThreadState(
                        List.of(workspace), Optional.empty(), List.of(thread), Optional.of(thread), Optional.empty()));
        ConversationThread other = new ConversationThread(
                com.javaclaw.api.ThreadId.random(),
                com.javaclaw.api.WorkspaceId.random(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                "其他",
                com.javaclaw.api.ThreadStatus.ACTIVE,
                1,
                DesktopTestFixtures.NOW,
                DesktopTestFixtures.NOW);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThreadState(
                        List.of(workspace),
                        Optional.of(workspace),
                        List.of(other),
                        Optional.of(other),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThreadState(
                        List.of(workspace),
                        Optional.of(workspace),
                        List.of(thread),
                        Optional.of(thread),
                        Optional.of(DesktopTestFixtures.turn(other, com.javaclaw.api.TurnStatus.RUNNING, 1))));
    }

    @Test
    void interactionStateCopiesCollectionsAndKeepsOnlyPendingApprovals() {
        AgentProfile profile = DesktopTestFixtures.profile();
        ArrayList<AgentProfile> profiles = new ArrayList<>(List.of(profile));
        InteractionState state = new InteractionState(
                profiles,
                Optional.of(profile),
                List.of(DesktopTestFixtures.approval(ApprovalState.PENDING)),
                new InputInteractionState(
                        List.of(DesktopTestFixtures.input()),
                        Optional.of(DesktopTestFixtures.input().request().id()),
                        Optional.empty(),
                        1),
                true,
                Optional.of("  错误  "));
        profiles.clear();

        assertEquals(List.of(profile), state.profiles());
        assertEquals(Optional.of("错误"), state.error());
        assertEquals(Optional.empty(), InteractionState.initial().error());
        AgentProfile missing = new AgentProfile(
                profile.id(), 2, ProfileLifecycle.ACTIVE, profile.spec(), profile.createdAt(), profile.updatedAt());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InteractionState(
                        List.of(profile),
                        Optional.of(missing),
                        List.of(),
                        InputInteractionState.initial(),
                        false,
                        Optional.empty()));
        AgentProfile disabled = new AgentProfile(
                profile.id(),
                profile.revision(),
                ProfileLifecycle.DISABLED,
                profile.spec(),
                profile.createdAt(),
                profile.updatedAt());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InteractionState(
                        List.of(disabled),
                        Optional.empty(),
                        List.of(),
                        InputInteractionState.initial(),
                        false,
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InteractionState(
                        List.of(profile),
                        Optional.of(profile),
                        List.of(DesktopTestFixtures.approval(ApprovalState.APPROVED)),
                        InputInteractionState.initial(),
                        false,
                        Optional.empty()));
    }

    @Test
    void inputInteractionStateRejectsTerminalDuplicateAndMissingOperationTargets() {
        var pending = DesktopTestFixtures.input();
        InputInteractionState state = new InputInteractionState(
                List.of(pending), Optional.of(pending.request().id()), Optional.empty(), 2);

        assertTrue(state.submitting(pending));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputInteractionState(List.of(pending, pending), Optional.empty(), Optional.empty(), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputInteractionState(List.of(pending), Optional.of("missing"), Optional.empty(), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputInteractionState(List.of(), Optional.empty(), Optional.empty(), -1));
        var resolved = new com.javaclaw.api.InputRequestRecord(
                pending.request(),
                com.javaclaw.api.InputRequestState.RESOLVED,
                2,
                Optional.of(new com.javaclaw.api.CanonicalPayload("{\"mode\":\"safe\"}")),
                Optional.empty(),
                DesktopTestFixtures.NOW);
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputInteractionState(List.of(resolved), Optional.empty(), Optional.empty(), 2));
    }

    @Test
    void inputProjectionDropsResponsesFromOlderRequestEpochs() {
        var request = DesktopTestFixtures.input();
        DesktopState loaded = DesktopStateProjection.inputs(DesktopState.initial(), List.of(request), 4);
        DesktopState submitting = DesktopStateProjection.inputOperation(loaded, request, 5);

        DesktopState staleList = DesktopStateProjection.inputs(submitting, List.of(), 4);
        DesktopState staleFailure = DesktopStateProjection.inputFailure(submitting, "旧错误", 4);

        assertEquals(submitting, staleList);
        assertEquals(submitting, staleFailure);
        assertEquals(
                Optional.of(request.request().id()),
                submitting.interaction().inputs().submittingRequestId());
        DesktopState failed = DesktopStateProjection.inputOperationFailure(submitting, "校验失败", 5);
        assertTrue(failed.interaction().inputs().submittingRequestId().isEmpty());
        assertEquals(Optional.of("校验失败"), failed.interaction().inputs().error());
        DesktopState completed = DesktopStateProjection.inputs(submitting, List.of(), 5);
        assertTrue(completed.interaction().inputs().pendingRequests().isEmpty());
        assertTrue(completed.interaction().inputs().submittingRequestId().isEmpty());
    }

    @Test
    void transcriptStoreAndViewValuesPublishImmutableSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> new TranscriptState(List.of(), -1, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TranscriptState(List.of(DesktopTestFixtures.item(2)), 1, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TranscriptState(List.of(DesktopTestFixtures.item(1), DesktopTestFixtures.item(1)), 1, true));

        DesktopStore store = new DesktopStore();
        ArrayList<DesktopState> observed = new ArrayList<>();
        store.subscribe(observed::add);
        store.update(state -> new DesktopState(
                ConnectionState.connecting(),
                state.navigation(),
                state.threads(),
                state.transcript(),
                state.interaction()));
        assertEquals(2, observed.size());
        assertEquals(
                ConnectionState.Status.CONNECTING, store.state().connection().status());
        assertThrows(NullPointerException.class, () -> store.subscribe(null));
        assertThrows(NullPointerException.class, () -> store.update(null));

        ArrayList<java.util.Map<String, Object>> rows = new ArrayList<>(List.of(java.util.Map.of("id", "1")));
        ViewData data = new ViewData(java.util.Map.of(
                "list",
                new ViewData.Source(
                        rows, java.util.Map.of("title", "页面"), "", "", false, 0, 0, java.util.Optional.empty())));
        rows.clear();
        assertEquals(1, data.source("list").rows().size());
        assertTrue(ViewData.empty().sources().isEmpty());
        PresentedItem item = new PresentedItem("标题", "正文", "style");
        assertEquals("标题", item.title());
        assertEquals("style", item.styleClass());
        assertThrows(NullPointerException.class, () -> new PresentedItem(null, "body", "style"));
        assertThrows(NullPointerException.class, () -> new PresentedItem("title", null, "style"));
        assertThrows(NullPointerException.class, () -> new PresentedItem("title", "body", null));
    }
}
