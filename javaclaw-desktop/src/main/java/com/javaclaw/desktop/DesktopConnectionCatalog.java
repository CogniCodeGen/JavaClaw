package com.javaclaw.desktop;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.Workspace;

/** 初次连接后一次性投影到 UI 的 SDK 目录快照。 */
record DesktopConnectionCatalog(
        List<Workspace> workspaces,
        Optional<Workspace> selectedWorkspace,
        List<ConversationThread> threads,
        Optional<ConversationThread> selectedThread,
        List<AgentProfile> profiles) {}
