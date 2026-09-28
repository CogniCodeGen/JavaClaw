package com.javaclaw.framework.spi;

/** Explicit treatment of a contribution in an on-demand plan. */
public interface OnDemandClassified {
    enum Classification { FIXED, DEFERRED, EAGER_ONLY }

    Classification classification();

    default String deferredSourceId() { return ""; }
}
