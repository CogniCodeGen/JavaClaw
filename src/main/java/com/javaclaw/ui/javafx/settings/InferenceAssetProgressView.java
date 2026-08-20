package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceAssetPreparationPort;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

/** Dispatches download/import progress updates to the JavaFX controls. */
final class InferenceAssetProgressView {
    private final FxDispatcher fx;
    private final ProgressBar bar;
    private final Label label;

    InferenceAssetProgressView(FxDispatcher fx, ProgressBar bar, Label label) {
        this.fx = fx;
        this.bar = bar;
        this.label = label;
    }

    void update(InferenceAssetPreparationPort.Progress value) {
        fx.dispatch(() -> {
            InferenceProfilePresentation.show(bar, true);
            InferenceProfilePresentation.show(label, true);
            bar.setProgress(value.totalBytes() <= 0 ? ProgressBar.INDETERMINATE_PROGRESS
                    : Math.min(1.0, (double) value.completedBytes() / value.totalBytes()));
            label.setText(value.phase() + (value.currentFile().isBlank()
                    ? "" : " · " + value.currentFile()) + (value.totalBytes() <= 0 ? ""
                    : " · " + InferenceModelPresentation.bytes(value.completedBytes()) + " / "
                    + InferenceModelPresentation.bytes(value.totalBytes())));
        });
    }
}
