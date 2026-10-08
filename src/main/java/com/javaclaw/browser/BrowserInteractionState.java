package com.javaclaw.browser;

/** 同一会话浏览器运行时共享的引用及操作锁，不随单次工具外观重建。 */
public final class BrowserInteractionState implements AutoCloseable {
    private final SnapshotManager snapshots = new SnapshotManager();
    private final BrowserOperationGate gate = new BrowserOperationGate();
    private PendingAuthentication authentication;
    private PendingAccountChoice accountChoice;
    private String answeredAuthentication = "";
    private String approvedAccount = "";
    private String authorizationRun = "";

    SnapshotManager snapshots() { return snapshots; }
    BrowserOperationGate gate() { return gate; }
    synchronized void bindAuthorizationRun(String runId) {
        if (!authorizationRun.equals(runId)) {
            authorizationRun = runId;
            answeredAuthentication = "";
            approvedAccount = "";
        }
    }

    synchronized PendingAuthentication authentication() { return authentication; }
    synchronized void authentication(PendingAuthentication value) { authentication = value; answeredAuthentication = ""; }
    synchronized void answerAuthentication(String challengeId) { answeredAuthentication = challengeId; }
    synchronized boolean authenticationAnswered(String challengeId) { return challengeId.equals(answeredAuthentication); }
    synchronized PendingAccountChoice accountChoice() { return accountChoice; }
    synchronized void accountChoice(PendingAccountChoice value) { accountChoice = value; approvedAccount = ""; }
    synchronized void approveAccount(String value) { approvedAccount = value; }
    synchronized boolean accountApproved(String value) { return !value.isBlank() && value.equals(approvedAccount); }
    synchronized void clearAuthentication() { authentication = null; answeredAuthentication = ""; }
    synchronized void clearAccountChoice() { accountChoice = null; approvedAccount = ""; }

    record PendingAuthentication(String challengeId, String targetUrl, String loginUrl,
                                 String baselineState, boolean protectedTargetObserved) { }
    record PendingAccountChoice(String challengeId, String origin, java.util.List<String> accountIds) {
        PendingAccountChoice { accountIds = java.util.List.copyOf(accountIds); }
    }

    @Override public void close() {
        gate.close(() -> { snapshots.clearRefs(); clearAuthentication(); clearAccountChoice(); });
    }
}
