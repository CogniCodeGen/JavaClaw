package com.javaclaw.core.api;

import java.util.List;

/** Runtime-owned inbox for input injected into an active turn. */
@FunctionalInterface
public interface TurnSteering {
    /** 取走当前已排队的追加输入；返回顺序批次，取走后不会在下一次 drain 重复返回。 */
    List<TurnInput> drain();

    TurnSteering NONE = List::of;
}
