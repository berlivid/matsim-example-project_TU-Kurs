package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.replanning.modules.SubtourModeChoice;

class OpenTourModeChoiceTestTest {

    @Test
    void testConfigHasExactlyFourApprovedDifferencesAndSupportedBehavior() throws Exception {
        String baseline = Files.readString(ValidateModeChoiceCalibrationConfig.CONFIG);
        String test = Files.readString(ValidateOpenTourModeChoiceTestConfig.CONFIG);
        ValidateOpenTourModeChoiceTestConfig.requireExactlyFourApprovedDifferences(baseline, test);

        Config config = ValidateOpenTourModeChoiceTestConfig.loadAndValidate(false);
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(5, config.controller().getLastIteration());
        assertEquals(ValidateOpenTourModeChoiceTestConfig.RUN_ID,
                config.controller().getRunId());
        assertEquals(ValidateOpenTourModeChoiceTestConfig.OUTPUT_DIRECTORY,
                config.controller().getOutputDirectory());
        assertEquals(SubtourModeChoice.Behavior.betweenAllAndFewerConstraints,
                config.subtourModeChoice().getBehavior());
    }

    @Test
    void existingOutputFailsClosed(@TempDir Path temp) throws Exception {
        Path output = temp.resolve("already-present");
        Files.createDirectory(output);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ValidateOpenTourModeChoiceTestConfig.requireOutputAbsent(output));
        assertTrue(failure.getMessage().contains("nothing was deleted"));
        assertTrue(Files.isDirectory(output));
    }

    @Test
    void validatorAcceptsCompleteZeroToFiveHistoryAndFinalFive(@TempDir Path temp)
            throws Exception {
        writeValidOutput(temp);
        var result = ValidateOpenTourModeChoiceTestOutput.validate(temp);
        assertEquals(107_618, result.openPersons());
        assertEquals(107_618, result.capablePersons());
        assertEquals(0, result.notCapablePersons());
        assertEquals(10, result.carEndsAway());
        assertEquals(5, result.bikeEndsAway());
    }

    @Test
    void missingOrDuplicateHistoryIterationFailsClosed(@TempDir Path temp) throws Exception {
        writeValidOutput(temp);
        Path history = temp.resolve("analysis/mode_choice_iteration_metrics.csv");
        String complete = Files.readString(history);
        Files.writeString(history, complete.replaceAll("(?m)^4,.*\\R", ""));
        assertThrows(IllegalStateException.class,
                () -> ValidateOpenTourModeChoiceTestOutput.validate(temp));

        writeValidOutput(temp);
        Path diagnostic = temp.resolve("analysis/open_tour_iteration_diagnostic.csv");
        String lines = Files.readString(diagnostic);
        String duplicate = lines.lines().filter(line -> line.startsWith("2,"))
                .findFirst().orElseThrow();
        Files.writeString(diagnostic, lines + duplicate + System.lineSeparator());
        assertThrows(IllegalStateException.class,
                () -> ValidateOpenTourModeChoiceTestOutput.validate(temp));
    }

    @Test
    void finalSummaryMustContainIterationFiveOnly(@TempDir Path temp) throws Exception {
        writeValidOutput(temp);
        Path summary = temp.resolve("analysis/mode_choice_final_summary.csv");
        Files.writeString(summary, Files.readString(summary).replace("\n5,", "\n4,"));
        assertThrows(IllegalStateException.class,
                () -> ValidateOpenTourModeChoiceTestOutput.validate(temp));
    }

    @Test
    void chainAuditDistinguishesJumpsFromPermittedOpenDayEnd() {
        Plan returnHome = plan("home", "car", "work", "car", "home");
        var closed = OpenTourModeChoiceTestDiagnostics.auditChainMode(returnHome, "car");
        assertEquals(0, closed.resourceJumps());
        assertFalse(closed.endsAwayFromInitial());
        assertTrue(closed.fullyVerifiable());

        Plan open = plan("home", "car", "work");
        var permittedEnd = OpenTourModeChoiceTestDiagnostics.auditChainMode(open, "car");
        assertEquals(0, permittedEnd.resourceJumps());
        assertTrue(permittedEnd.endsAwayFromInitial());

        Plan jump = plan("home", "car", "work", "walk", "other", "car", "home");
        var invalid = OpenTourModeChoiceTestDiagnostics.auditChainMode(jump, "car");
        assertEquals(1, invalid.resourceJumps());
    }

    private static Plan plan(String... locationsAndModes) {
        var factory = PopulationUtils.getFactory();
        Plan plan = factory.createPlan();
        plan.addActivity(activity(locationsAndModes[0]));
        for (int index = 1; index < locationsAndModes.length; index += 2) {
            Leg leg = factory.createLeg(locationsAndModes[index]);
            plan.addLeg(leg);
            plan.addActivity(activity(locationsAndModes[index + 1]));
        }
        return plan;
    }

    private static Activity activity(String location) {
        return PopulationUtils.getFactory().createActivityFromLinkId(
                "activity", Id.createLinkId(location));
    }

    private static void writeValidOutput(Path output) throws Exception {
        Path analysis = output.resolve("analysis");
        Files.createDirectories(analysis);
        StringBuilder history = new StringBuilder(
                "iteration,spatial_scope,plan_eligibility,metric,mode,value,unit\n");
        for (int iteration = 0; iteration <= 5; iteration++) {
            history.append(iteration).append(",ALL_TRIPS,ALL_PLANS,invalid_stage_distances,all,0,stages\n")
                    .append(iteration).append(",ALL_TRIPS,ALL_PLANS,invalid_main_trip_distances,all,0,trips\n")
                    .append(iteration).append(",BOTH_INSIDE,ALL_PLANS,trip_modal_share,unknown,0,percent\n");
        }
        Files.writeString(analysis.resolve("mode_choice_iteration_metrics.csv"), history);
        Files.writeString(analysis.resolve("mode_choice_final_summary.csv"),
                history.toString().lines().filter(line -> line.startsWith("iteration,")
                        || line.startsWith("5,")).reduce("", (left, right) -> left + right + "\n"));

        StringBuilder diagnostic = new StringBuilder("iteration,metric,mode,value,unit\n");
        for (int iteration = 0; iteration <= 5; iteration++) {
            row(diagnostic, iteration, "original_open_persons", "all", 107_618);
            row(diagnostic, iteration, "mode_choice_capable_persons", "all", 107_618);
            row(diagnostic, iteration, "still_not_mode_choice_capable_persons", "all", 0);
            row(diagnostic, iteration, "capability_location_unverifiable", "all", 0);
            row(diagnostic, iteration, "mode_choice_capable_main_trips", "all", 107_618);
            row(diagnostic, iteration, "mode_choice_capable_both_inside_main_trips", "all", 37_417);
            row(diagnostic, iteration, "still_not_mode_choice_capable_main_trips", "all", 0);
            row(diagnostic, iteration, "still_not_mode_choice_capable_both_inside_main_trips", "all", 0);
            row(diagnostic, iteration, "cohort_both_inside_main_trips", "all", 37_417);
            row(diagnostic, iteration, "persons_with_changed_mode_signature", "all", 100);
            row(diagnostic, iteration, "unknown_main_modes", "unknown", 0);
            row(diagnostic, iteration, "stuck_events_cumulative", "all", 0);
            long[] baseline = {49_700, 10_054, 36_223, 11_641};
            long[] inside = {12_968, 4_088, 15_853, 4_508};
            for (int mode = 0; mode < 4; mode++) {
                String name = List.of("car", "pt", "walk", "bike").get(mode);
                row(diagnostic, iteration, "baseline_main_trips", name, baseline[mode]);
                row(diagnostic, iteration, "baseline_both_inside_main_trips", name, inside[mode]);
                row(diagnostic, iteration, "current_main_trips", name, baseline[mode]);
            }
            for (String mode : List.of("car", "bike")) {
                row(diagnostic, iteration, "chain_resource_jump_persons", mode, 0);
                row(diagnostic, iteration, "chain_location_unverifiable_persons", mode, 0);
                row(diagnostic, iteration, "chain_end_away_from_initial_persons", mode,
                        "car".equals(mode) ? 10 : 5);
            }
        }
        Files.writeString(analysis.resolve("open_tour_iteration_diagnostic.csv"), diagnostic);
        Files.writeString(analysis.resolve("open_tour_test_completion.csv"),
                "last_iteration,unexpected_shutdown,stuck_events\n5,false,0\n");
        Files.writeString(output.resolve("test.logfile.log"),
                "INFO regular controller shutdown completed\n");
    }

    private static void row(StringBuilder csv, int iteration, String metric,
                            String mode, long value) {
        csv.append(iteration).append(',').append(metric).append(',').append(mode)
                .append(',').append(value).append(",count\n");
    }
}
