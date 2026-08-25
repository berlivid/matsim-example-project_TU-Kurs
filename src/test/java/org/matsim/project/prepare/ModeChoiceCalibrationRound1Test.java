package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModeChoiceCalibrationRound1Test {

    @Test
    void roundConfigHasOnlyApprovedDifferencesAndConstants() throws Exception {
        String baseline = Files.readString(ValidateModeChoiceCalibrationConfig.CONFIG);
        String round = Files.readString(ValidateModeChoiceCalibrationRound1Config.CONFIG);
        ValidateModeChoiceCalibrationRound1Config.requireOnlyApprovedDifferences(
                baseline, round);
        var config = ValidateModeChoiceCalibrationRound1Config.loadAndValidate(true);
        assertEquals(0.0, config.scoring().getModes().get("car").getConstant());
        assertEquals(0.89, config.scoring().getModes().get("pt").getConstant());
        assertEquals(0.78, config.scoring().getModes().get("walk").getConstant());
        assertEquals(-0.21, config.scoring().getModes().get("bike").getConstant());
        assertEquals("fromSpecifiedModesToSpecifiedModes",
                config.subtourModeChoice().getBehavior().toString());
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(20, config.controller().getLastIteration());
    }

    @Test
    void unrelatedConfigDifferenceFailsClosed() throws Exception {
        String baseline = Files.readString(ValidateModeChoiceCalibrationConfig.CONFIG);
        String round = Files.readString(ValidateModeChoiceCalibrationRound1Config.CONFIG)
                .replace("<param name=\"randomSeed\" value=\"4711\" />",
                        "<param name=\"randomSeed\" value=\"7\" />");
        assertThrows(IllegalStateException.class, () ->
                ValidateModeChoiceCalibrationRound1Config.requireOnlyApprovedDifferences(
                        baseline, round));
    }

    @Test
    void documentaryXmlCommentDifferencesAreIgnored() throws Exception {
        String baseline = Files.readString(ValidateModeChoiceCalibrationConfig.CONFIG);
        String round = Files.readString(ValidateModeChoiceCalibrationRound1Config.CONFIG)
                .replace("<!-- Separate, first mode-choice calibration round for the synthetic 2019 reference. -->",
                        "<!-- Different documentary wording that has no MATSim semantics. -->")
                .replace("<!-- Round-1 mode-specific constants; car remains the reference alternative -->",
                        "<!-- Another harmless multiline\n                     documentation comment. -->")
                .replace("    <module name=\"global\">",
                        "    <!-- An additional test-only comment. -->\n"
                                + "    <module name=\"global\">");
        ValidateModeChoiceCalibrationRound1Config.requireOnlyApprovedDifferences(
                baseline, round);
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
    void outputSummaryUsesIterationsSixteenToTwentyAndReportsStuckEvents(
            @TempDir Path output) throws Exception {
        writeValidOutput(output);
        var summary = ValidateAndSummarizeModeChoiceCalibrationRound1
                .validateAndSummarize(output, true);
        assertEquals(34.0, summary.lateMean().get("car"), 1e-12);
        assertEquals(32.0, summary.lateMin().get("car"), 1e-12);
        assertEquals(36.0, summary.lateMax().get("car"), 1e-12);
        assertEquals(0.0, summary.lateDifference().get("pt"), 1e-12);
        assertEquals(60.0, summary.finalPkmShares().get("car"), 1e-12);
        assertEquals(5L, summary.stuckEvents().get(20));
        String csv = Files.readString(output.resolve(
                "analysis/mode_choice_round_1_summary.csv"));
        assertTrue(csv.contains("late_window,16-20,car,mean_trip_modal_share,34.000000000,percent"));
        assertTrue(csv.contains("stuck_events,20,all,event_count,5.000000000,events"));
        assertThrows(IllegalStateException.class, () ->
                ValidateAndSummarizeModeChoiceCalibrationRound1
                        .validateAndSummarize(output, true));
    }

    @Test
    void duplicateOrMissingIterationFailsClosed(@TempDir Path output) throws Exception {
        writeValidOutput(output);
        Path history = output.resolve("analysis/mode_choice_iteration_metrics.csv");
        String original = Files.readString(history);
        String duplicate = original.lines().filter(line -> line.startsWith("20,"))
                .findFirst().orElseThrow();
        Files.writeString(history, original + duplicate + "\n");
        assertThrows(IllegalStateException.class, () ->
                ValidateAndSummarizeModeChoiceCalibrationRound1
                        .validateAndSummarize(output, false));

        Files.writeString(history, original.lines().filter(line -> !line.startsWith("19,"))
                .reduce("", (left, right) -> left + right + "\n"));
        assertThrows(IllegalStateException.class, () ->
                ValidateAndSummarizeModeChoiceCalibrationRound1
                        .validateAndSummarize(output, false));
    }

    private static void writeValidOutput(Path output) throws Exception {
        Path analysis = output.resolve("analysis");
        Files.createDirectories(analysis);
        String header = "iteration,spatial_scope,plan_eligibility,metric,mode,value,unit\n";
        StringBuilder history = new StringBuilder(header);
        for (int iteration = 0; iteration <= 20; iteration++) {
            double car = iteration >= 16 ? 52 - iteration : 34;
            double pt = iteration >= 16 ? iteration + 6 : 24;
            rows(history, iteration, car, pt);
        }
        Files.writeString(analysis.resolve("mode_choice_iteration_metrics.csv"), history);
        StringBuilder summary = new StringBuilder(header);
        rows(summary, 20, 32, 26);
        Files.writeString(analysis.resolve("mode_choice_final_summary.csv"), summary);

        StringBuilder stuck = new StringBuilder("iteration,leg_mode,time_window,event_count,"
                + "unique_persons,population_share_percent,min_event_time_seconds,"
                + "max_event_time_seconds,cumulative_event_count,cumulative_unique_persons,"
                + "qsim_end_time_seconds\n");
        long cumulative = 0;
        for (int iteration = 0; iteration <= 20; iteration++) {
            long events = Math.max(0, iteration - 15);
            cumulative += events;
            stuck.append(iteration).append(",all,")
                    .append(events == 0 ? "NO_EVENTS" : "ALL_WINDOWS").append(',')
                    .append(events).append(',').append(events).append(",0.0,,,")
                    .append(cumulative).append(',').append(cumulative)
                    .append(",154800.0\n");
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
    }

    private static void metric(StringBuilder csv, int iteration, String scope,
                               String metric, String mode, double value) {
        csv.append(iteration).append(',').append(scope).append(",ALL_PLANS,")
                .append(metric).append(',').append(mode).append(',').append(value)
                .append(",test\n");
    }
}
