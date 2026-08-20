package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelAsset;

import java.util.function.BooleanSupplier;

/** Verifies a managed content-addressed asset immediately before a cold model start. */
public interface InferenceAssetIntegrityPort {

    void verifyForColdStart(InferenceModelAsset asset, BooleanSupplier cancelled) throws Exception;
}
