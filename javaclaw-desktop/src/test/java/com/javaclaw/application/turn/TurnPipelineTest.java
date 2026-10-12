package com.javaclaw.application.turn;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TurnPipelineTest {

    @Test
    void executesStagesInDeclaredOrder() {
        List<String> visited = new ArrayList<>();
        TurnPipeline<List<String>> pipeline = new TurnPipeline<>(List.of(
                new TurnStage<>("vision", state -> state.add("vision")),
                new TurnStage<>("routing", state -> state.add("routing")),
                new TurnStage<>("stream", state -> state.add("stream"))));

        pipeline.execute(visited);

        assertEquals(List.of("vision", "routing", "stream"), visited);
        assertEquals(List.of("vision", "routing", "stream"), pipeline.stageIds());
    }

    @Test
    void rejectsDuplicateIdsAndStopsAtFailure() {
        assertThrows(IllegalArgumentException.class, () -> new TurnPipeline<>(List.of(
                new TurnStage<>("same", state -> {
                }),
                new TurnStage<>("same", state -> {
                }))));

        List<String> visited = new ArrayList<>();
        TurnPipeline<List<String>> pipeline = new TurnPipeline<>(List.of(
                new TurnStage<>("first", state -> state.add("first")),
                new TurnStage<>("broken", state -> { throw new Exception("boom"); }),
                new TurnStage<>("late", state -> state.add("late"))));

        TurnPipeline.TurnStageException failure = assertThrows(
                TurnPipeline.TurnStageException.class, () -> pipeline.execute(visited));
        assertEquals("broken", failure.stageId());
        assertEquals(List.of("first"), visited);
    }
}
