package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunProfileDraft;

import java.util.Objects;

/** Read-only profile template installed by a trusted extension. */
public record RunProfileContribution(RunProfileDraft profile) {
    public RunProfileContribution {
        profile = Objects.requireNonNull(profile, "profile");
    }
}
