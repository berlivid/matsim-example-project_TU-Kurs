package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModeChoiceCalibrationRound2Test {

    @Test
    void roundConfigHasOnlyApprovedDifferencesAndConstants() throws Exception {
        String round1 = Files.readString(ValidateModeChoiceCalibrationRound1Config.CONFIG);
        String round2 = Files.readString(ValidateModeChoiceCalibrationRound2Config.CONFIG);
        ValidateModeChoiceCalibrationRound2Config.requireOnlyApprovedDifferences(round1, round2);
        var config = ValidateModeChoiceCalibrationRound2Config.loadAndValidate(true);
        assertEquals(0.0, config.scoring().getModes().get("car").getConstant());
        assertEquals(1.27, config.scoring().getModes().get("pt").getConstant());
        assertEquals(1.27, config.scoring().getModes().get("walk").getConstant());
        assertEquals(-0.34, config.scoring().getModes().get("bike").getConstant());
        assertEquals(0.6, config.replanning()
                .getFractionOfIterationsToDisableInnovation());
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(40, config.controller().getLastIteration());
        assertEquals("fromSpecifiedModesToSpecifiedModes",
                config.subtourModeChoice().getBehavior().toString());
    }

    @Test
    void commentsAreIgnoredButAdditionalParameterDifferenceFailsClosed() throws Exception {
        String round1 = Files.readString(ValidateModeChoiceCalibrationRound1Config.CONFIG);
        String round2 = Files.readString(ValidateModeChoiceCalibrationRound2Config.CONFIG);
        ValidateModeChoiceCalibrationRound2Config.requireOnlyApprovedDifferences(round1,
                round2.replace("<module name=\"global\">",
                        "<!-- Test-only wording. --><module name=\"global\">"));
        assertThrows(IllegalStateException.class, () ->
                ValidateModeChoiceCalibrationRound2Config.requireOnlyApprovedDifferences(
                        round1, round2.replace("<module name=\"global\">",
                                "<module name=\"global\"><param name=\"testOnly\" value=\"1\" />")));
    }

    @Test
    void productiveAndRoundOneConfigsRemainValid() throws Exception {
        ValidateModeChoiceCalibrationConfig.loadAndValidate();
        ValidateModeChoiceCalibrationRound1Config.loadAndValidate(false);
        assertFalse(Files.readString(ValidateModeChoiceCalibrationRound1Config.CONFIG)
                .contains("betweenAllAndFewerConstraints"));
    }

    @Test
    void outputProtectionNeverDeletesExistingDirectory(@TempDir Path temp) throws Exception {
        Path output = temp.resolve("output");
        Files.createDirectory(output);
        Files.writeString(output.resolve("evidence.txt"), "keep");
        assertThrows(IllegalStateException.class,
                () -> ModeChoiceCalibrationRunSupport.requireOutputAbsent(output));
        assertEquals("keep", Files.readString(output.resolve("evidence.txt")));
    }

    @Test
    void outputSummaryUsesIterationsThirtyOneToFortyAndComputesTrend(
            @TempDir Path output) throws Exception {
        writeValidOutput(output);
        var summary = ValidateAndSummarizeModeChoiceCalibrationRound2
                .validateAndSummarize(output, true);
        assertEquals(34.0, summary.lateMean().get("car"), 1e-12);
        assertEquals(33.1, summary.lateMin().get("car"), 1e-12);
        assertEquals(34.9, summary.lateMax().get("car"), 1e-12);
        assertEquals(1.8, summary.lateRange().get("car"), 1e-12);
        assertEquals(0.2, summary.lateTrend().get("car"), 1e-12);
        assertEquals(60.0, summary.finalPkmShares().get("car"), 1e-12);
        assertEquals(7.5, summary.finalMeanTripDistances().get("car"), 1e-12);
        assertEquals(10L, summary.stuckEvents().get(40));
        String csv = Files.readString(output.resolve(
                "analysis/mode_choice_round_2_summary.csv"));
        assertTrue(csv.contains("late_window,31-40,car,range_trip_modal_share,1.800000000"));
        assertTrue(csv.contains("late_window,31-40,car,linear_trend,0.200000000"));
        assertTrue(csv.contains("secondary_validation,40,car,final_mean_trip_distance,7.500000000"));
        assertThrows(IllegalStateException.class, () ->
                ValidateAndSummarizeModeChoiceCalibrationRound2
                        .validateAndSummarize(output, true));
    }

    @Test
    void missingIterationFailsClosed(@TempDir Path output) throws Exception {
        writeValidOutput(output);
        Path history = output.resolve("analysis/mode_choice_iteration_metrics.csv");
        String original = Files.readString(history);
        Files.writeString(history, original.lines().filter(line -> !line.startsWith("39,"))
                .reduce("", (left, right) -> left + right + "\n"));
        assertThrows(IllegalStateException.class, () ->
                ValidateAndSummarizeModeChoiceCalibrationRound2
                        .validateAndSummarize(output, false));
    }

    private static void writeValidOutput(Path output) throws Exception {
        Path analysis = output.resolve("analysis");
        Files.createDirectories(analysis);
        String header = "iteration,spatial_scope,plan_eligibility,metric,mode,value,unit\n";
        StringBuilder history = new StringBuilder(header);
        for (int iteration = 0; iteration <= 40; iteration++) {
            double car = iteration >= 31 ? 33.1 + 0.2 * (iteration - 31) : 34.0;
            rows(history, iteration, car, 76.0 - car - 18.0);
        }
        Files.writeString(analysis.resolve("mode_choice_iteration_metrics.csv"), history);
        StringBuilder summary = new StringBuilder(header);
        rows(summary, 40, 34.9, 23.1);
        Files.writeString(analysis.resolve("mode_choice_final_summary.csv"), summary);

        StringBuilder stuck = new StringBuilder("iteration,leg_mode,time_window,event_count,"
                + "unique_persons,population_share_percent,min_event_time_seconds,"
                + "max_event_time_seconds,cumulative_event_count,cumulative_unique_persons,"
                + "qsim_end_time_seconds\n");
        long cumulative = 0;
        for (int iteration = 0; iteration <= 40; iteration++) {
            long events = Math.max(0, iteration - 30);
            cumulative += events;
            stuck.append(iteration).append(",all,")
                    .append(events == 0 ? "NO_EVENTS" : "ALL_WINDOWS").append(',')
                    .append(events).append(',').append(events).append(",0.0,,,")
                    .append(cumulative).append(',').append(cumulative).append(",154800.0\n");
        }
        Files.writeString(analysis.resolve("stuck_events_iteration_metrics.csv"), stuck);
        Files.writeString(output.resolve("round.logfile.log"),
                "INFO S H U T D O W N   ---   shutdown completed.\n");
    }

    private static void rows(StringBuilder csv, int iteration, double car, double pt) {
        metric(csv, iteration, "ALL_TRIPS", "valid_persons", "all",
                ModeChoiceCalibrationIterationListener.EXPECTED_PERSONS);
        metric(csv, iteration, "ALL_TRIPS", "valid_main_trips", "all",
                ModeChoiceCalibrationIterationListener.EXPECTED_MAIN_TRIPS);
        metric(csv, iteration, "BOTH_INSIDE", "valid_main_trips", "all",
                ModeChoiceCalibrationIterationListener.EXPECTED_BOTH_INSIDE_TRIPS);
        metric(csv, iteration, "BOTH_INSIDE", "trip_modal_share", "car", car);
        metric(csv, iteration, "BOTH_INSIDE", "trip_modal_share", "pt", pt);
        metric(csv, iteration, "BOTH_INSIDE", "trip_modal_share", "bike", 18);
        metric(csv, iteration, "BOTH_INSIDE", "trip_modal_share", "walk", 24);
        metric(csv, iteration, "BOTH_INSIDE", "trip_modal_share", "unknown", 0);
        metric(csv, iteration, "ALL_TRIPS", "invalid_stage_distances", "all", 0);
        metric(csv, iteration, "ALL_TRIPS", "invalid_main_trip_distances", "all", 0);
        metric(csv, iteration, "BOTH_INSIDE", "main_mode_pkm_unscaled_5pct", "car", 60);
        metric(csv, iteration, "BOTH_INSIDE", "main_mode_pkm_unscaled_5pct", "pt", 25);
        metric(csv, iteration, "BOTH_INSIDE", "main_mode_pkm_unscaled_5pct", "bike", 10);
        metric(csv, iteration, "BOTH_INSIDE", "main_mode_pkm_unscaled_5pct", "walk", 5);
        metric(csv, iteration, "BOTH_INSIDE", "mean_trip_distance", "car", 7.5);
        metric(csv, iteration, "BOTH_INSIDE", "mean_trip_distance", "pt", 8.5);
        metric(csv, iteration, "BOTH_INSIDE", "mean_trip_distance", "bike", 4.5);
        metric(csv, iteration, "BOTH_INSIDE", "mean_trip_distance", "walk", 1.5);
    }

    private static void metric(StringBuilder csv, int iteration, String scope,
                               String metric, String mode, double value) {
        csv.append(iteration).append(',').append(scope).append(",ALL_PLANS,")
                .append(metric).append(',').append(mode).append(',').append(value)
                .append(",test\n");
    }
}
