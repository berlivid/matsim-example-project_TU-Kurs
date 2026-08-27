package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.AnalysisResult;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.DistanceSource;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.GroupKey;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.MetricSnapshot;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.PlanEligibility;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.SpatialScope;

class ResidentModeChoiceCalibrationRound4Test {

    @Test
    void formulaReproducesExpectedConstantsFromPreservedLateMeans() throws Exception {
        var validation = ResidentModeChoiceRound4Specification.validate();
        assertEquals(61, validation.iterations());
        assertEquals(68_770, validation.residents());
        assertEquals(137_540, validation.residentTrips());
        assertEquals(51, validation.lateFirstIteration());
        assertEquals(60, validation.lateLastIteration());
        assertEquals(0.180757242031606,
                ResidentModeChoiceRound4Specification.cumulativeConstant("pt"), 1e-14);
        assertEquals(0.568301786101787,
                ResidentModeChoiceRound4Specification.cumulativeConstant("bike"), 1e-14);
        assertEquals(3.452586783310460,
                ResidentModeChoiceRound4Specification.cumulativeConstant("walk"), 1e-14);
    }

    @Test
    void configDiffersFromRound3OnlyInRunControlAndApprovedConstants()
            throws Exception {
        Config round3 = ValidateResidentModeChoiceCalibrationRound3Config
                .loadAndValidateStructure(false);
        Config round4 = ValidateResidentModeChoiceCalibrationRound4Config
                .loadAndValidateStructure(true);
        Set<String> differences = RunMatsim2019ResidentModeChoiceIteration0Validation
                .differences(
                        RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round3),
                        RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round4));
        assertEquals(ValidateResidentModeChoiceCalibrationRound4Config.APPROVED_DIFFERENCES,
                differences);
        assertEquals(0, round4.controller().getFirstIteration());
        assertEquals(60, round4.controller().getLastIteration());
        assertEquals(0.8,
                round4.replanning().getFractionOfIterationsToDisableInnovation(), 1e-12);
        assertEquals(4, round4.replanning().getMaxAgentPlanMemorySize());
        assertEquals(48,
                ResidentModeChoiceRound4Specification
                        .EXPECTED_INNOVATION_DISABLE_AFTER_ITERATION);
        assertEquals(round3.plans().getInputFile(), round4.plans().getInputFile());
        assertEquals(ResidentModeChoiceRound4Specification.ROUND_4_CONSTANTS,
                ValidateResidentModeChoiceCalibrationRound4Config.constants(round4));
        assertEquals(100.0, ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT
                .values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
        assertFalse(Files.exists(ValidateResidentModeChoiceCalibrationRound4Config.OUTPUT));

        round4.global().setRandomSeed(12);
        Set<String> unsafe = RunMatsim2019ResidentModeChoiceIteration0Validation.differences(
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round3),
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round4));
        assertTrue(unsafe.contains("module[global]/@randomSeed"));
        assertFalse(unsafe.equals(
                ValidateResidentModeChoiceCalibrationRound4Config.APPROVED_DIFFERENCES));
    }

    @Test
    void outputProtectionAndRunConfigurationsAreFailClosedAndSyntactic(
            @TempDir Path temp) throws Exception {
        assertThrows(IllegalStateException.class,
                () -> ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(temp));
        var builder = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder();
        for (String name : List.of(
                "R4A Validate 2019 Resident Mode Choice Calibration Round 4.run.xml",
                "R4B Run 2019 Resident Mode Choice Calibration Round 4.run.xml",
                "R4C Validate and Summarize 2019 Resident Mode Choice Calibration Round 4.run.xml")) {
            assertEquals("component", builder.parse(Path.of(".run", name).toFile())
                    .getDocumentElement().getTagName());
        }
    }

    @Test
    void round4UsesLateWindowAndWritesRound3Comparison(@TempDir Path temp)
            throws Exception {
        Path output = temp.resolve("round4");
        List<AnalysisResult> results = new ArrayList<>();
        for (int iteration = 0; iteration <= 60; iteration++) {
            results.add(result(iteration, 34.0));
        }
        new ResidentModeChoiceCalibrationAnalysisWriter(output).write(results, true);
        writeStuckHistory(output, 0.001);
        Path round3 = temp.resolve("round3-analysis");
        Files.createDirectories(round3);
        writeRound3Review(round3);

        var review = ResidentModeChoiceRound2Review.validateAndWriteRound4(output, round3);
        assertEquals("CALIBRATED", review.overallStatus());
        assertEquals("CONVERGED", review.modes().get("car").convergenceStatus());
        assertEquals("WITHIN_TARGET_TOLERANCE",
                review.modes().get("car").targetFitStatus());
        String late = Files.readString(output.resolve(
                "analysis/resident_mode_choice_late_iteration_statistics.csv"));
        assertTrue(late.contains("resident_trip_share,car,51,60,10,"));
        assertTrue(Files.isRegularFile(output.resolve(
                "analysis/resident_mode_choice_round_4_calibration_review.csv")));
        assertTrue(Files.readString(output.resolve(
                        "analysis/resident_mode_choice_round_3_vs_round_4.csv"))
                .contains("car,0.000000000,0.000000000"));
        String history = Files.readString(output.resolve(
                "analysis/resident_mode_choice_round_2_to_4_history.csv"));
        assertTrue(history.contains("2,car,0.000000000"));
        assertTrue(history.contains("3,car,0.000000000"));
        assertTrue(history.contains("4,car,0.000000000"));
    }

    @Test
    void specificationAndConfigValidationDoNotChangeRound3Evidence() throws Exception {
        Map<Path, String> before = round3Hashes();
        ResidentModeChoiceRound4Specification.validate();
        ValidateResidentModeChoiceCalibrationRound4Config.loadAndValidateStructure(true);
        assertEquals(before, round3Hashes());
    }

    private static Map<Path, String> round3Hashes() throws Exception {
        LinkedHashMap<Path, String> hashes = new LinkedHashMap<>();
        try (var paths = Files.list(ResidentModeChoiceRound4Specification.ROUND_3_ANALYSIS)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                hashes.put(path.getFileName(),
                        ValidateResidentModeChoiceCalibrationConfig.sha256(path));
            }
        }
        return Map.copyOf(hashes);
    }

    private static void writeStuckHistory(Path output, double lateShare) throws Exception {
        Path stuck = output.resolve(
                "analysis/resident_stuck_events_by_iteration_and_mode.csv");
        StringBuilder csv = new StringBuilder(
                "iteration,routing_mode,event_count,unique_affected_residents,affected_resident_main_trips,resident_person_share_percent,resident_main_trip_share_percent,difference_from_iteration_0_events,difference_from_iteration_0_persons,difference_from_iteration_0_trips,cumulative_resident_events,review_status\n");
        for (int iteration = 0; iteration <= 60; iteration++) {
            double share = iteration >= 51 ? lateShare : 0.001;
            csv.append(iteration).append(",all,1,1,1,0.001,").append(share)
                    .append(",0,0,0,").append(iteration + 1).append(",PASS\n");
        }
        Files.writeString(stuck, csv);
    }

    private static void writeRound3Review(Path analysis) throws Exception {
        StringBuilder csv = new StringBuilder(
                "mode,late_mean_trip_share_percent,late_minimum_trip_share_percent,late_maximum_trip_share_percent,late_range_pp,late_trend_pp_per_iteration,final_trip_share_percent,trip_target_percent,late_mean_trip_difference_pp,final_trip_difference_pp,late_mean_pkm_share_percent,final_pkm_share_percent,pkm_target_percent,final_pkm_difference_pp,convergence_status,target_fit_status\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            double target = ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get(mode);
            csv.append(mode).append(',').append(target).append(',').append(target)
                    .append(',').append(target).append(",0,0,").append(target)
                    .append(',').append(target).append(",0,0,25,25,25,0,CONVERGED,")
                    .append("WITHIN_TARGET_TOLERANCE\n");
        }
        Files.writeString(analysis.resolve(
                "resident_mode_choice_round_3_calibration_review.csv"), csv);
    }

    private static AnalysisResult result(int iteration, double carShare) {
        long total = 10_000;
        long car = Math.round(carShare * 100);
        long pt = 2_400;
        long bike = 1_800;
        long walk = total - car - pt - bike;
        Map<String, Long> trips = Map.of(
                "car", car, "pt", pt, "bike", bike, "walk", walk);
        Map<String, Double> metres = Map.of(
                "car", car * 1_000.0, "pt", pt * 1_000.0,
                "bike", bike * 1_000.0, "walk", walk * 1_000.0);
        MetricSnapshot metrics = new MetricSnapshot(1, total, trips, trips, Map.of(), 0,
                trips, metres, metres,
                new EnumMap<>(Map.of(DistanceSource.ROUTE_REPORTED, total)), 0, 0);
        return new AnalysisResult(iteration,
                Map.of(new GroupKey(SpatialScope.ALL_TRIPS, PlanEligibility.ALL_PLANS),
                        metrics), 1, 0, Set.of());
    }
}

