package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.*;
import java.util.List;
import java.util.Optional;

/** Transactional task aggregate and ordered event journal. */
public interface ThreadStore {
    ThreadSnapshot create(ThreadStartRequest request);
    Optional<ThreadSnapshot> find(RunScope scope);
    List<ThreadSnapshot> list(String workspace, String user, boolean archived);
    ThreadSnapshot setStatus(RunScope scope, ThreadStatus status);
    ThreadSnapshot configure(RunScope scope, ThreadConfiguration configuration);
    List<RunSnapshot> turns(RunScope scope);
    List<ThreadEvent> events(RunScope scope, long afterSequence);
    ThreadSnapshot fork(RunScope source, TurnId throughTurn, String title);
    List<RunScope> markDeleting(RunScope root);
    void purge(RunScope scope);
}
