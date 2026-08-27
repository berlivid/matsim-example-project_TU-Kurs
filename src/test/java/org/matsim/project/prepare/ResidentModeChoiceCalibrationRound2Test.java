package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
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

class ResidentModeChoiceCalibrationRound2Test {

    @Test
    void formulaReproducesCumulativeConstantsAndRound1Evidence() throws Exception {
        var validation = ResidentModeChoiceRound2Specification.validate();
        assertEquals(41, validation.iterations());
        assertEquals(68_770, validation.residents());
        assertEquals(137_540, validation.residentTrips());
        assertEquals(7, validation.affectedTrips());
        assertEquals(0.923960894451889,
                ResidentModeChoiceRound2Specification.undampedUpdate("pt"), 1e-14);
        assertEquals(0.461980447225944,
                ResidentModeChoiceRound2Specification.dampedUpdate("pt"), 1e-14);
        assertEquals(0.853797447225944,
                ResidentModeChoiceRound2Specification.cumulativeConstant("pt"), 1e-14);
        assertEquals(-0.095617284557718,
                ResidentModeChoiceRound2Specification.cumulativeConstant("bike"), 1e-14);
        assertEquals(1.756683601048696,
                ResidentModeChoiceRound2Specification.cumulativeConstant("walk"), 1e-14);
    }

    @Test
    void round2ConfigContainsOnlyApprovedDifferencesAndOriginalPopulation()
            throws Exception {
        Config round1 = ValidateResidentModeChoiceCalibrationRound1Config
                .loadAndValidateStructure(false);
        Config round2 = ValidateResidentModeChoiceCalibrationRound2Config
                .loadAndValidateStructure(true);
        Set<String> differences = RunMatsim2019ResidentModeChoiceIteration0Validation
                .differences(
                        RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round1),
                        RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round2));
        assertEquals(ValidateResidentModeChoiceCalibrationRound2Config.APPROVED_DIFFERENCES,
                differences);
        assertEquals(60, round2.controller().getLastIteration());
        assertEquals(48 * 3600.0, round2.qsim().getEndTime().seconds(), 1e-12);
        assertEquals(0.8,
                round2.replanning().getFractionOfIterationsToDisableInnovation(), 1e-12);
        assertEquals(48,
                ResidentModeChoiceRound2Specification.EXPECTED_INNOVATION_DISABLE_ITERATION);
        assertEquals(round1.plans().getInputFile(), round2.plans().getInputFile());
        assertEquals(ResidentModeChoiceRound2Specification.ROUND_2_CONSTANTS,
                ValidateResidentModeChoiceCalibrationRound2Config.constants(round2));
        assertFalse(Files.exists(ValidateResidentModeChoiceCalibrationRound2Config.OUTPUT));

        round2.qsim().setFlowCapFactor(0.06);
        Set<String> unsafe = RunMatsim2019ResidentModeChoiceIteration0Validation.differences(
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round1),
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round2));
        assertTrue(unsafe.contains("module[qsim]/@flowCapacityFactor"));
        assertFalse(unsafe.equals(
                ValidateResidentModeChoiceCalibrationRound2Config.APPROVED_DIFFERENCES));
    }

    @Test
    void outputProtectionAndRunConfigurationsAreFailClosedAndSyntactic(
            @TempDir Path temp) throws Exception {
        assertThrows(IllegalStateException.class,
                () -> ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(temp));
        var builder = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder();
        for (String name : List.of(
                "R2A Validate 2019 Resident Mode Choice Calibration Round 2.run.xml",
                "R2B Run 2019 Resident Mode Choice Calibration Round 2.run.xml",
                "R2C Analyze 2019 Resident Mode Choice Calibration Round 2.run.xml")) {
            assertEquals("component", builder.parse(Path.of(".run", name).toFile())
                    .getDocumentElement().getTagName());
        }
    }

    @Test
    void round2LateWindowAndExactTargetsProduceCalibrated(@TempDir Path temp)
            throws Exception {
        writeHistory(temp, 34.0);
        writeStuckHistory(temp, 0.001);
        var review = ResidentModeChoiceRound2Review.validateAndWrite(temp);
        assertEquals("CALIBRATED", review.overallStatus());
        assertEquals("CONVERGED", review.modes().get("car").convergenceStatus());
        assertEquals("WITHIN_TARGET_TOLERANCE",
                review.modes().get("car").targetFitStatus());
        String late = Files.readString(temp.resolve(
                "analysis/resident_mode_choice_late_iteration_statistics.csv"));
        assertTrue(late.contains("resident_trip_share,car,51,60,10,"));
        assertTrue(late.contains("CONVERGED,WITHIN_TARGET_TOLERANCE"));
    }

    @Test
    void stableButFarFromTargetIsNotLabelledPass(@TempDir Path temp) throws Exception {
        writeHistory(temp, 45.0);
        writeStuckHistory(temp, 0.001);
        var review = ResidentModeChoiceRound2Review.validateAndWrite(temp);
        assertEquals("CONVERGED", review.modes().get("car").convergenceStatus());
        assertEquals("OUTSIDE_TARGET_TOLERANCE",
                review.modes().get("car").targetFitStatus());
        assertEquals("REVIEW_REQUIRED", review.overallStatus());
        String report = Files.readString(temp.resolve(
                "analysis/resident_mode_choice_round_2_calibration_report.md"));
        assertFalse(report.contains("| PASS |"));
    }

    @Test
    void trendOrStuckThresholdPreventsCalibrated(@TempDir Path temp) throws Exception {
        List<AnalysisResult> results = new ArrayList<>();
        for (int iteration = 0; iteration <= 60; iteration++) {
            results.add(result(iteration, 34.0 + 0.2 * iteration));
        }
        new ResidentModeChoiceCalibrationAnalysisWriter(temp).write(results, true);
        writeStuckHistory(temp, 1.000000001);
        var review = ResidentModeChoiceRound2Review.validateAndWrite(temp);
        assertEquals("NOT_CONVERGED", review.modes().get("car").convergenceStatus());
        assertEquals("OUTSIDE_STUCK_THRESHOLD", review.stuckStatus());
        assertEquals("REVIEW_REQUIRED", review.overallStatus());
    }

    private static void writeHistory(Path output, double carShare) throws Exception {
        List<AnalysisResult> results = new ArrayList<>();
        for (int iteration = 0; iteration <= 60; iteration++) {
            results.add(result(iteration, carShare));
        }
        new ResidentModeChoiceCalibrationAnalysisWriter(output).write(results, true);
    }

    private static void writeStuckHistory(Path output, double lateShare) throws Exception {
        Path stuck = output.resolve(
                "analysis/resident_stuck_events_by_iteration_and_mode.csv");
        StringBuilder csv = new StringBuilder(
                "iteration,routing_mode,event_count,unique_affected_residents,affected_resident_main_trips,resident_person_share_percent,resident_main_trip_share_percent,difference_from_iteration_0_events,difference_from_iteration_0_persons,difference_from_iteration_0_trips,cumulative_resident_events,review_status\n");
        for (int iteration = 0; iteration <= 60; iteration++) {
            double share = iteration >= 51 ? lateShare : 0.001;
            csv.append(iteration).append(",all,1,1,1,0.001,").append(share)
                    .append(",0,0,0,").append(iteration + 1)
                    .append(share > 1.0 ? ",REVIEW_REQUIRED\n" : ",PASS\n");
        }
        Files.writeString(stuck, csv);
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
