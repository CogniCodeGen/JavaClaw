package com.javaclaw.loop;

import com.javaclaw.loop.model.CompletionCheck;
import com.javaclaw.loop.model.IterationResult;

import java.util.HashSet;
import java.util.Set;

/** Tracks progress from criterion results and tool action identities only. */
public final class ProgressGate {

    private int highWaterSatisfied = -1;
    private Set<String> previousToolFingerprints = Set.of();
    private boolean firstRound = true;

    /** Advance the comparison baseline once for each successful iteration. */
    public boolean madeProgress(CompletionCheck check, IterationResult result) {
        if (result.threw()) return false;
        boolean criteriaAdvanced = check.satisfied() > highWaterSatisfied;
        Set<String> fingerprints = new HashSet<>(result.toolCalls());
        boolean newAction = !fingerprints.isEmpty() && !fingerprints.equals(previousToolFingerprints);
        boolean repeatedAction = !fingerprints.isEmpty() && fingerprints.equals(previousToolFingerprints);
        boolean first = firstRound;
        firstRound = false;
        highWaterSatisfied = Math.max(highWaterSatisfied, check.satisfied());
        previousToolFingerprints = fingerprints;
        if (first || criteriaAdvanced) return true;
        return !repeatedAction && newAction;
    }

    /** An unaccepted current Run or an unmet explicit criterion requires another round. */
    public boolean hasRemaining(CompletionCheck check, IterationResult result) {
        return !check.done() && result.taskResult() != null;
    }
}
