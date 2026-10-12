package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunRequest;

import java.util.List;
import java.util.Objects;

/** Classifies context contributions for newly compiled on-demand plans. */
public final class OnDemandContributions {
    private OnDemandContributions() {}

    public static PromptContributor fixed(PromptContributor delegate) {
        return new FixedPrompt(delegate);
    }

    public static PromptContributor deferred(PromptContributor delegate, String sourceId) {
        return new DeferredPrompt(delegate, required(sourceId));
    }

    public static RetrieverContribution deferred(RetrieverContribution delegate, String sourceId) {
        return new DeferredRetriever(delegate, required(sourceId));
    }

    public static ContextProvider deferred(ContextProvider delegate, String sourceId) {
        return new DeferredProvider(delegate, required(sourceId));
    }

    public static PromptContributor eagerOnly(PromptContributor delegate) {
        return new EagerPrompt(delegate);
    }

    private static String required(String value) {
        String result = Objects.requireNonNull(value, "sourceId").strip();
        if (result.isEmpty()) throw new IllegalArgumentException("sourceId must not be blank");
        return result;
    }

    private record FixedPrompt(PromptContributor delegate)
            implements PromptContributor, OnDemandClassified {
        private FixedPrompt { Objects.requireNonNull(delegate, "delegate"); }
        @Override public String contribute(RunRequest request, ExtensionStateView state) {
            return delegate.contribute(request, state);
        }
        @Override public Classification classification() { return Classification.FIXED; }
    }

    private record DeferredPrompt(PromptContributor delegate, String deferredSourceId)
            implements PromptContributor, OnDemandClassified {
        private DeferredPrompt { Objects.requireNonNull(delegate, "delegate"); }
        @Override public String contribute(RunRequest request, ExtensionStateView state) {
            return delegate.contribute(request, state);
        }
        @Override public Classification classification() { return Classification.DEFERRED; }
    }

    private record DeferredRetriever(RetrieverContribution delegate, String deferredSourceId)
            implements RetrieverContribution, OnDemandClassified {
        private DeferredRetriever { Objects.requireNonNull(delegate, "delegate"); }
        @Override public List<JsonNode> retrieve(String query, RunRequest request) {
            return delegate.retrieve(query, request);
        }
        @Override public Classification classification() { return Classification.DEFERRED; }
    }

    private record DeferredProvider(ContextProvider delegate, String deferredSourceId)
            implements ContextProvider, OnDemandClassified {
        private DeferredProvider { Objects.requireNonNull(delegate, "delegate"); }
        @Override public List<JsonNode> provide(RunRequest request, ExtensionStateView state) {
            return delegate.provide(request, state);
        }
        @Override public Classification classification() { return Classification.DEFERRED; }
    }

    private record EagerPrompt(PromptContributor delegate)
            implements PromptContributor, OnDemandClassified {
        private EagerPrompt { Objects.requireNonNull(delegate, "delegate"); }
        @Override public String contribute(RunRequest request, ExtensionStateView state) {
            return delegate.contribute(request, state);
        }
        @Override public Classification classification() { return Classification.EAGER_ONLY; }
    }
}
