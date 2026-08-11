package com.javaclaw.task.sdd.verify;

import com.javaclaw.task.sdd.spec.Criterion;
import com.javaclaw.task.sdd.spec.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioVerifierBehaviorTest {

    @TempDir
    Path workDirectory;

    @Test
    void artifactPredicatesResolveRelativeAndAbsolutePathsConservatively() throws Exception {
        Path artifact = Files.writeString(workDirectory.resolve("result.txt"), "ready");
        ScenarioVerifier verifier = new ScenarioVerifier(workDirectory.toString(), null, null);

        assertTrue(verifier.verify(scenario(Criterion.ARTIFACT_EXISTS, "result.txt")).passed());
        assertTrue(verifier.verify(scenario(
                Criterion.ARTIFACT_EXISTS, artifact.toString())).passed());
        assertFalse(verifier.verify(scenario(
                Criterion.ARTIFACT_EXISTS, "missing.txt")).passed());
        assertFalse(verifier.verify(scenario(Criterion.ARTIFACT_EXISTS, " ")).passed());
        assertFalse(verifier.verify(scenario(Criterion.ARTIFACT_EXISTS, "\0")).passed());

        ScenarioVerifier withoutWorkDirectory = new ScenarioVerifier(null, null, null);
        assertFalse(withoutWorkDirectory.verify(
                scenario(Criterion.ARTIFACT_EXISTS, "relative.txt")).passed());
    }

    @Test
    void commandPredicatesRequireRunnerAndPreserveExitEvidence() {
        ScenarioVerifier unavailable = new ScenarioVerifier(workDirectory.toString(), null, null);
        assertFalse(unavailable.verify(
                scenario(Criterion.COMMAND_EXIT_ZERO, "compile")).passed());
        assertFalse(unavailable.verify(
                scenario(Criterion.COMMAND_EXIT_ZERO, " ")).passed());

        ScenarioVerifier passing = new ScenarioVerifier(workDirectory.toString(),
                (command, cwd) -> new CommandRunner.Result(0, "ok"), null);
        VerificationOutcome success = passing.verify(
                scenario(Criterion.COMMAND_EXIT_ZERO, " compile "));
        assertTrue(success.passed());
        assertTrue(success.deterministic());
        assertTrue(success.detail().contains("退出码=0"));

        ScenarioVerifier failing = new ScenarioVerifier(workDirectory.toString(),
                (command, cwd) -> new CommandRunner.Result(7, "failed"), null);
        assertFalse(failing.verify(
                scenario(Criterion.COMMAND_EXIT_ZERO, "compile")).passed());

        ScenarioVerifier throwing = new ScenarioVerifier(workDirectory.toString(),
                (command, cwd) -> { throw new IllegalStateException("runner unavailable"); }, null);
        VerificationOutcome exception = throwing.verify(
                scenario(Criterion.COMMAND_EXIT_ZERO, "compile"));
        assertFalse(exception.passed());
        assertTrue(exception.detail().contains("runner unavailable"));
    }

    @Test
    void outputPredicatesHandleContentNullOutputAndBuildBannerSelfHealing() {
        ScenarioVerifier unavailable = new ScenarioVerifier(workDirectory.toString(), null, null);
        assertFalse(unavailable.verify(scenario(
                Criterion.OUTPUT_CONTAINS, "build ||| ready")).passed());

        ScenarioVerifier output = new ScenarioVerifier(workDirectory.toString(),
                (command, cwd) -> switch (command) {
                    case "contains" -> new CommandRunner.Result(1, "prefix ready suffix");
                    case "null-output" -> new CommandRunner.Result(0, null);
                    case "build-ok" -> new CommandRunner.Result(0, "quiet");
                    default -> new CommandRunner.Result(2, "BUILD SUCCESSFUL");
                }, null);

        assertTrue(output.verify(scenario(
                Criterion.OUTPUT_CONTAINS, "contains ||| ready")).passed());
        assertFalse(output.verify(scenario(
                Criterion.OUTPUT_CONTAINS, "contains ||| absent")).passed());
        assertFalse(output.verify(scenario(
                Criterion.OUTPUT_CONTAINS, "null-output ||| ready")).passed());
        assertTrue(output.verify(scenario(
                Criterion.OUTPUT_CONTAINS, "build-ok ||| build success")).passed());
        assertFalse(output.verify(scenario(
                Criterion.OUTPUT_CONTAINS, "build-fail ||| BUILD SUCCESSFUL")).passed());
    }

    @Test
    void descriptiveAndMalformedScenariosUseTheCriticWithoutDefaultApproval() {
        Scenario freeform = scenario(Criterion.FREEFORM, "visually correct");
        ScenarioVerifier unavailable = new ScenarioVerifier(workDirectory.toString(), null, null);
        VerificationOutcome conservative = unavailable.verify(freeform);
        assertFalse(conservative.passed());
        assertFalse(conservative.deterministic());

        ScenarioVerifier critic = new ScenarioVerifier(workDirectory.toString(), null,
                value -> new CriticJudge.Verdict(
                        value.title().contains("pass"), "reviewed"));
        assertTrue(critic.verify(namedScenario("pass case", null)).passed());
        assertFalse(critic.verify(namedScenario(
                "fail case", new Criterion("unknown", null))).passed());
        assertFalse(critic.verify(scenario(
                Criterion.OUTPUT_CONTAINS, "no structured separator")).passed());

        ScenarioVerifier throwing = new ScenarioVerifier(workDirectory.toString(), null,
                value -> { throw new IllegalArgumentException("critic failed"); });
        assertTrue(throwing.verify(freeform).detail().contains("critic failed"));

        assertEquals(List.of(), unavailable.verifyAll(null));
        assertEquals(2, critic.verifyAll(List.of(
                namedScenario("pass one", null), namedScenario("fail two", null))).size());
    }

    @Test
    void criterionAndOutcomeValueObjectsExposeDeterministicSemantics() {
        assertEquals(Criterion.FREEFORM, new Criterion(null, "x").normalizedType());
        assertEquals(Criterion.FREEFORM, new Criterion(" ", "x").normalizedType());
        assertEquals(Criterion.ARTIFACT_EXISTS,
                new Criterion(" ARTIFACT_EXISTS ", "x").normalizedType());
        assertTrue(new Criterion(Criterion.ARTIFACT_EXISTS, "x").isDeterministic());
        assertTrue(new Criterion(Criterion.COMMAND_EXIT_ZERO, "x").isDeterministic());
        assertTrue(new Criterion(Criterion.OUTPUT_CONTAINS, "x").isDeterministic());
        assertFalse(Criterion.freeform("x").isDeterministic());

        Scenario value = scenario(Criterion.FREEFORM, "x");
        assertTrue(VerificationOutcome.pass(value, false, "ok").passed());
        assertFalse(VerificationOutcome.fail(value, true, "no").passed());
        assertTrue(new CommandRunner.Result(0, "").success());
        assertFalse(new CommandRunner.Result(-1, "").success());
    }

    private static Scenario scenario(String type, String predicate) {
        return namedScenario("scenario", new Criterion(type, predicate));
    }

    private static Scenario namedScenario(String title, Criterion criterion) {
        return new Scenario(title, "given", "when", "then", criterion);
    }
}
