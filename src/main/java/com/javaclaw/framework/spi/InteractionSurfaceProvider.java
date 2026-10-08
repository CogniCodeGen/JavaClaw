package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.InteractionSurfaceEvent;
import java.util.List;
import java.util.function.Consumer;

/** Supplemental host identity only; registry admits this SPI only for exact host tool classes. */
public interface InteractionSurfaceProvider {
    default void bindInteractionObserver(Consumer<InteractionSurfaceEvent> observer) { }
    default List<InteractionSurfaceEvent> currentInteractionSurfaces() { return List.of(); }
}
