package com.javaclaw.framework.api;

import java.util.List;

/** Persistent task lifecycle. Every operation carries an explicit isolation scope. */
public interface ThreadClient {
    ThreadSnapshot start(ThreadStartRequest request);
    ThreadSnapshot get(RunScope scope);
    List<ThreadSnapshot> list(String workspaceId, String userId, boolean includeArchived);
    ThreadSnapshot resume(RunScope scope);
    ThreadSnapshot archive(RunScope scope);
    ThreadSnapshot fork(RunScope source, TurnId throughTurn, String title);
    void delete(RunScope scope);
    List<RunSnapshot> turns(RunScope scope);
    List<ThreadEvent> events(RunScope scope, long afterSequence);
    ThreadSnapshot configure(RunScope scope, ThreadConfiguration configuration);
}
