package com.javaclaw.framework.springai;

import com.javaclaw.framework.api.ModelTokenUsage;

/** Provider usage normalized for token accounting while retaining the legacy pricing input. */
record ExtractedModelUsage(ModelTokenUsage usage, long pricingInputTokens) {
    ExtractedModelUsage {
        usage = usage == null ? ModelTokenUsage.ZERO : usage;
        pricingInputTokens = Math.max(0, pricingInputTokens);
    }
}
